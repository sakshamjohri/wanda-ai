package com.saksham.wanda.web;

import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.Attachment;
import com.saksham.wanda.service.FileService;
import com.saksham.wanda.service.UploadStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST   /api/upload       the raw file is the request body, its name is in the X-File-Name header
 * DELETE /api/upload/{id}  discard a file that was not sent yet
 * The processed file waits in the {@link UploadStore} until it is sent with a message.
 */
public class UploadServlet extends BaseServlet {

    private final FileService files;
    private final UploadStore uploads;

    public UploadServlet(FileService files, UploadStore uploads) {
        this.files = files;
        this.uploads = uploads;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        String header = req.getHeader("X-File-Name");
        String name = header == null ? "file" : URLDecoder.decode(header, StandardCharsets.UTF_8);

        byte[] bytes;
        try (InputStream in = req.getInputStream()) {
            bytes = in.readNBytes(FileService.MAX_BYTES + 1);
        }
        Attachment file = files.process(name, bytes);
        uploads.put(userId, file);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", file.id());
        out.put("name", file.name());
        out.put("kind", file.kind());
        out.put("size", file.size());
        if (file.kind() == Attachment.Kind.IMAGE) {
            out.put("preview", file.dataUrl());
        }
        writeJson(resp, out);
    }

    /** Removes an attachment the user discarded before sending. */
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        String id = req.getPathInfo() == null ? "" : req.getPathInfo().substring(1);
        if (!uploads.remove(userId, id)) {
            throw new ValidationException(404, "Attachment not found");
        }
        writeJson(resp, Map.of("ok", true));
    }
}
