using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Windows.Forms;

internal static class StandaloneInstaller
{
    private const string PayloadResource = "VSMPClient.Payload.zip";
    private const string ExecutableName = "V-Client.exe";
    private const string UninstallResource = "VSMPClient.Uninstall.ps1";

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 1 && args[0] == "--verify")
        {
            return VerifyPayload();
        }

        try
        {
            string installDirectory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Programs",
                "VSMP Client");
            string executablePath = Path.Combine(installDirectory, ExecutableName);

            // --silent is how the launcher updates itself: it exits right after starting this installer, so
            // wait for it to finish closing instead of asking the user to, and show no setup window.
            bool silent = args.Contains("--silent");
            if (silent)
            {
                for (int waited = 0; waited < 60 && IsInstalledLauncherRunning(executablePath); waited++)
                {
                    System.Threading.Thread.Sleep(500);
                }
            }

            bool launcherRunning = IsInstalledLauncherRunning(executablePath);
            if (launcherRunning)
            {
                throw new InvalidOperationException(
                    "Close V-Client before installing or updating it, then run this setup file again.");
            }

            if (silent)
            {
                ExtractPayload(installDirectory, null);
            }
            else
            {
                RunWithProgress(installDirectory);
            }
            CreateShortcuts(installDirectory, executablePath);
            RegisterUninstaller(installDirectory, executablePath);
            Process.Start(new ProcessStartInfo
            {
                FileName = executablePath,
                WorkingDirectory = installDirectory,
                UseShellExecute = true
            });
            return 0;
        }
        catch (Exception exception)
        {
            if (args.Contains("--silent"))
            {
                // A failed background update must not strand the user: start whatever version is installed.
                string existing = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "Programs", "VSMP Client", ExecutableName);
                if (File.Exists(existing) && !IsInstalledLauncherRunning(existing))
                {
                    Process.Start(new ProcessStartInfo { FileName = existing, UseShellExecute = true });
                }
                return 1;
            }

            MessageBox.Show(
                "V-Client could not be installed or started.\r\n\r\n" + exception.Message,
                "VSMP Client Setup",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return 1;
        }
    }

    /// <summary>
    /// True only when the launcher installed at <paramref name="executablePath"/> is running. Any other process that
    /// happens to be called "V-Client" (a copy run from another folder, this setup file renamed, an unrelated program)
    /// does not hold the install folder's files, so it must not block the install.
    /// </summary>
    private static bool IsInstalledLauncherRunning(string executablePath)
    {
        string installed = Path.GetFullPath(executablePath);
        int ownId = Process.GetCurrentProcess().Id;
        foreach (Process candidate in Process.GetProcessesByName("V-Client"))
        {
            try
            {
                if (candidate.Id == ownId) continue;
                string path = candidate.MainModule.FileName;
                if (string.Equals(Path.GetFullPath(path), installed, StringComparison.OrdinalIgnoreCase)) return true;
            }
            catch (Exception)
            {
                // Access denied or exited meanwhile: cannot be shown to be the installed copy, so it is not counted.
            }
        }
        return false;
    }

    private static int VerifyPayload()
    {
        try
        {
            using (Stream payload = OpenPayload())
            using (ZipArchive archive = new ZipArchive(payload, ZipArchiveMode.Read))
            {
                string[] requiredEntries =
                {
                    "Release/V-Client.exe",
                    "Release/app/V-Client.cfg",
                    "Release/runtime/lib/modules",
                    "Release/runtime/bin/java.exe"
                };
                string[] entries = archive.Entries.Select(entry => entry.FullName).ToArray();
                foreach (string requiredEntry in requiredEntries)
                {
                    if (!entries.Contains(requiredEntry, StringComparer.Ordinal))
                    {
                        throw new InvalidDataException(
                            "The embedded application bundle is missing " + requiredEntry + ".");
                    }
                }

                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    if (!entry.FullName.StartsWith("Release/", StringComparison.Ordinal))
                    {
                        throw new InvalidDataException(
                            "The embedded application bundle contains an unexpected path.");
                    }

                    using (Stream file = entry.Open())
                    {
                        file.CopyTo(Stream.Null);
                    }
                }
            }

            string verificationDirectory = Path.Combine(
                Path.GetTempPath(), "VSMP-Setup-Verify-" + Guid.NewGuid().ToString("N"));
            try
            {
                ExtractPayload(verificationDirectory, null);
                string runtimeModules = Path.Combine(verificationDirectory, "runtime", "lib", "modules");
                string javaRuntime = Path.Combine(
                    verificationDirectory, "runtime", "bin", "java.exe");
                if (!File.Exists(runtimeModules) || !File.Exists(javaRuntime))
                {
                    throw new InvalidDataException(
                        "The extracted bundle is missing a required Java runtime file.");
                }
            }
            finally
            {
                if (Directory.Exists(verificationDirectory))
                {
                    Directory.Delete(verificationDirectory, true);
                }
            }

            return 0;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine(exception);
            return 1;
        }
    }

    /// <summary>Extracts the bundle on a worker thread while a progress window is shown.</summary>
    private static void RunWithProgress(string installDirectory)
    {
        Application.EnableVisualStyles();
        Exception failure = null;
        using (Form window = new Form())
        using (Label message = new Label())
        using (ProgressBar bar = new ProgressBar())
        {
            window.Text = "VSMP Client Setup";
            window.FormBorderStyle = FormBorderStyle.FixedDialog;
            window.MaximizeBox = false;
            window.MinimizeBox = false;
            window.StartPosition = FormStartPosition.CenterScreen;
            window.ClientSize = new System.Drawing.Size(420, 96);
            window.ControlBox = false;
            message.SetBounds(16, 16, 388, 22);
            message.Text = "Installing V-Client...";
            bar.SetBounds(16, 48, 388, 22);
            bar.Minimum = 0;
            bar.Maximum = 100;
            window.Controls.Add(message);
            window.Controls.Add(bar);
            window.Shown += (sender, arguments) =>
            {
                System.Threading.ThreadPool.QueueUserWorkItem(state =>
                {
                    try
                    {
                        ExtractPayload(installDirectory, percent => window.BeginInvoke((Action)(() =>
                        {
                            bar.Value = percent;
                            message.Text = "Installing V-Client... " + percent + "%";
                        })));
                    }
                    catch (Exception exception)
                    {
                        failure = exception;
                    }
                    window.BeginInvoke((Action)(() => window.Close()));
                });
            };
            Application.Run(window);
        }
        if (failure != null) throw failure;
    }

    private static void ExtractPayload(string installDirectory, Action<int> onProgress)
    {
        string installRoot = Path.GetFullPath(installDirectory) + Path.DirectorySeparatorChar;
        Directory.CreateDirectory(installDirectory);

        using (Stream payload = OpenPayload())
        using (ZipArchive archive = new ZipArchive(payload, ZipArchiveMode.Read))
        {
            long totalBytes = Math.Max(1, archive.Entries.Sum(entry => entry.Length));
            long writtenBytes = 0;
            int reported = -1;
            foreach (ZipArchiveEntry entry in archive.Entries)
            {
                writtenBytes += entry.Length;
                int percent = (int)(writtenBytes * 100 / totalBytes);
                if (onProgress != null && percent != reported)
                {
                    reported = percent;
                    onProgress(Math.Min(100, percent));
                }
                if (!entry.FullName.StartsWith("Release/", StringComparison.Ordinal)
                    || entry.FullName.StartsWith("Release/Source/", StringComparison.Ordinal))
                {
                    continue;
                }

                string relativePath = entry.FullName.Substring("Release/".Length)
                    .Replace('/', Path.DirectorySeparatorChar);
                if (relativePath.Length == 0)
                {
                    continue;
                }

                string destination = Path.GetFullPath(Path.Combine(installDirectory, relativePath));
                if (!destination.StartsWith(installRoot, StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidDataException(
                        "The application bundle contains an unsafe file path.");
                }

                if (entry.FullName.EndsWith("/", StringComparison.Ordinal))
                {
                    Directory.CreateDirectory(destination);
                    continue;
                }

                string parent = Path.GetDirectoryName(destination);
                if (parent == null)
                {
                    throw new InvalidDataException("Could not determine an application file's directory.");
                }

                Directory.CreateDirectory(parent);
                using (Stream input = entry.Open())
                using (FileStream output = new FileStream(destination, FileMode.Create, FileAccess.Write))
                {
                    input.CopyTo(output);
                }
            }
        }

        string executablePath = Path.Combine(installDirectory, ExecutableName);
        if (!File.Exists(executablePath))
        {
            throw new FileNotFoundException(
                "The application bundle did not contain V-Client.exe.", executablePath);
        }
    }

    /// <summary>Adds V-Client to the Start menu app list and the desktop. Failure here must never fail the install.</summary>
    private static void CreateShortcuts(string installDirectory, string executablePath)
    {
        try
        {
            string startMenu = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs");
            string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            WriteShortcut(Path.Combine(startMenu, "V-Client.lnk"), installDirectory, executablePath);
            WriteShortcut(Path.Combine(desktop, "V-Client.lnk"), installDirectory, executablePath);
        }
        catch (Exception)
        {
        }
    }

    /// <summary>Lists V-Client in Settings > Apps and Start with an Uninstall command. Never fails the install.</summary>
    private static void RegisterUninstaller(string installDirectory, string executablePath)
    {
        try
        {
            string script = Path.Combine(installDirectory, "uninstall.ps1");
            using (Stream resource = Assembly.GetExecutingAssembly().GetManifestResourceStream(UninstallResource))
            {
                if (resource == null) return;
                using (FileStream output = new FileStream(script, FileMode.Create, FileAccess.Write))
                {
                    resource.CopyTo(output);
                }
            }

            string command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + script
                + "\" -InstallDir \"" + installDirectory + "\"";
            using (Microsoft.Win32.RegistryKey key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Uninstall\VSMPClient"))
            {
                key.SetValue("DisplayName", "V-Client");
                key.SetValue("Publisher", "VSMP");
                key.SetValue("DisplayIcon", executablePath + ",0");
                key.SetValue("InstallLocation", installDirectory);
                key.SetValue("UninstallString", command);
                key.SetValue("NoModify", 1, Microsoft.Win32.RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, Microsoft.Win32.RegistryValueKind.DWord);
                key.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
            }
        }
        catch (Exception)
        {
        }
    }

    private static void WriteShortcut(string path, string installDirectory, string executablePath)
    {
        Type shellType = Type.GetTypeFromProgID("WScript.Shell");
        if (shellType == null) return;
        object shell = Activator.CreateInstance(shellType);
        object link = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { path });
        Type linkType = link.GetType();
        linkType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, link, new object[] { executablePath });
        linkType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, link, new object[] { installDirectory });
        linkType.InvokeMember("Description", BindingFlags.SetProperty, null, link, new object[] { "V-Client launcher for VSMP" });
        linkType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, link, new object[] { executablePath + ",0" });
        linkType.InvokeMember("Save", BindingFlags.InvokeMethod, null, link, null);
    }

    private static Stream OpenPayload()
    {
        Stream payload = Assembly.GetExecutingAssembly()
            .GetManifestResourceStream(PayloadResource);
        if (payload == null)
        {
            throw new InvalidDataException("The setup file does not contain the application bundle.");
        }

        return payload;
    }
}
