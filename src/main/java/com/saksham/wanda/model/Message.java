package com.saksham.wanda.model;

import java.util.List;

/**
 * A single message inside a conversation.
 * {@code content} is what the user sees; {@code context} holds text extracted from
 * attached files, which is sent to the model but not repeated in the chat bubble.
 */
public class Message extends BaseEntity {

    /** Who wrote the message, mapped to the role names the chat API expects. */
    public enum Role {
        USER("user"), ASSISTANT("assistant"), SYSTEM("system");

        private final String apiName;

        Role(String apiName) { this.apiName = apiName; }

        public String apiName() { return apiName; }
    }

    private long conversationId;
    private Role role;
    private String content;
    private String attachments;          // comma separated file names, for display
    private transient String context;    // extracted file text, for the model only
    private String kind = "text";        // "text", or "image" for a picture request and the picture made from it
    private int imageCount;              // how many pictures were attached (the pictures stay on the server)
    private transient String images;     // picture data URLs separated by newlines, loaded only when needed

    public Message() { }

    public Message(long conversationId, Role role, String content) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
    }

    /** Full text sent to the model: the visible message plus any file context. */
    public String promptText() {
        if (isImage()) {
            return role == Role.ASSISTANT ? "[Created an image from this description: " + content + "]"
                                          : "Create an image: " + content;
        }
        return context == null || context.isBlank() ? content : content + "\n\n" + context;
    }

    public long getConversationId() { return conversationId; }
    public void setConversationId(long conversationId) { this.conversationId = conversationId; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }

    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public boolean isImage() { return "image".equals(kind); }

    public int getImageCount() { return imageCount; }
    public void setImageCount(int imageCount) { this.imageCount = imageCount; }

    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }

    /** The attached pictures as data URLs (empty unless {@link #setImages} was called). */
    public List<String> pictureList() {
        return images == null || images.isBlank() ? List.of() : List.of(images.split("\\n"));
    }

    @Override
    public String label() { return role.apiName(); }
}
