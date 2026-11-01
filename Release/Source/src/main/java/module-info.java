module com.vclient {
    requires java.desktop;
    requires java.logging;
    requires java.naming;
    requires java.net.http;
    requires jdk.management;
    requires com.sun.jna;
    requires com.sun.jna.platform;
    requires javafx.controls;
    requires javafx.media;
    requires javafx.web;
    requires com.fasterxml.jackson.databind;

    exports com.vclient;

    provides java.net.spi.InetAddressResolverProvider with com.vclient.DohResolverProvider;
}
