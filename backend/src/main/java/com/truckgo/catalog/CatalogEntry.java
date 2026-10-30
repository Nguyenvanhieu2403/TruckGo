package com.truckgo.catalog;

/** Common shape of catalog rows an admin can activate or deactivate. */
public interface CatalogEntry {
    String getCode();
    boolean isActive();
    void setActive(boolean active);
}
