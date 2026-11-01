package com.vclient;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;

/** What one browser tab shows: a page, with the few controls the Browser tab needs. */
interface TabContent {
    Node node();

    void load(String url);

    void reload();

    void back();

    void forward();

    void focus();

    /** Only the tab on screen needs to keep drawing. */
    void setActive(boolean active);

    void close();

    StringProperty titleProperty();

    StringProperty locationProperty();

    BooleanProperty loadingProperty();
}
