package com.vclient;

import java.time.LocalDate;
import java.util.List;

/**
 * Profile badges. Early User and Developer are worked out here. OG User, Beta Tester and Bug Hunter are awarded by
 * the VSMP team, so nothing on this PC can unlock them; they stay locked until the backend hands them out.
 */
final class Badges {
    record Badge(String id, String title, String how, boolean automatic) {}

    /** Players who first ran VYRN before this day are Early Users. */
    static final LocalDate EARLY_USER_CUTOFF = LocalDate.of(2026, 11, 10);

    static final List<Badge> ALL = List.of(
            new Badge("early", "Early User", "Started using VYRN before the launch on 10 Nov 2026.", true),
            new Badge("og", "OG User", "Awarded by the VSMP team to the original community.", false),
            new Badge("beta", "Beta Tester", "Awarded by the VSMP team to testers of pre-release builds.", false),
            new Badge("bug", "Bug Hunter", "Awarded by the VSMP team for reporting bugs.", false),
            new Badge("dev", "Developer", "The VYRN developer account.", true));

    private Badges() {}

    static boolean earned(Badge badge, LauncherSettings settings, PlayerAccount account, PlayerStats stats) {
        return switch (badge.id()) {
            case "early" -> stats.firstSeen() != null && stats.firstSeen().isBefore(EARLY_USER_CUTOFF);
            case "dev" -> settings.isAdmin(account);
            default -> false;
        };
    }
}
