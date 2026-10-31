package com.truckgo.geo;

/** The provider could not answer (network, quota, key rejected). Never turned into a made-up result. */
public class GeocodingUnavailableException extends RuntimeException {
    public GeocodingUnavailableException(String message) { super(message); }
}
