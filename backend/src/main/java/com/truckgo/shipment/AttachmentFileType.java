package com.truckgo.shipment;

/** GOODS_PHOTO is added by the customer on a draft; the others are proof photos taken by the assigned driver. */
public enum AttachmentFileType {
    GOODS_PHOTO, PICKUP_PHOTO, DELIVERY_PHOTO, INCIDENT_PHOTO;

    public boolean isTripPhoto() { return this != GOODS_PHOTO; }
}
