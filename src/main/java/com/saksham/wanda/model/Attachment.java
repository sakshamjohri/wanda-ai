package com.saksham.wanda.model;

/**
 * A file the user uploaded for the next message. Held in the HTTP session until it is sent.
 *
 * @param id      random id the browser refers to
 * @param name    original file name
 * @param kind    TEXT (text was extracted) or IMAGE (sent to the model as a picture)
 * @param text    extracted text, null for images
 * @param dataUrl base64 data URL, null for text files
 * @param size    size in bytes
 */
public record Attachment(String id, String name, Kind kind, String text, String dataUrl, long size) {

    public enum Kind { TEXT, IMAGE }
}
