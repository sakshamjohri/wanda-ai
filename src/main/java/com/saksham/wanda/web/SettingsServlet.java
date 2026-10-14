package com.saksham.wanda.web;

import com.google.gson.reflect.TypeToken;
import com.saksham.wanda.ai.ProviderFactory;
import com.saksham.wanda.service.AuthService;
import com.saksham.wanda.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * GET /api/settings returns preferences; PUT /api/settings saves the keys provided.
 * Responses also say whether chat replies come from Groq or the setup guide (no key yet).
 */
public class SettingsServlet extends BaseServlet {

    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() { }.getType();

    private final SettingsService settings;
    private final AuthService auth;
    private final ProviderFactory providers;

    public SettingsServlet(SettingsService settings, AuthService auth, ProviderFactory providers) {
        this.settings = settings;
        this.auth = auth;
        this.providers = providers;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        writeJson(resp, view(requireUser(req)));
    }

    private Map<String, Object> view(long userId) {
        Map<String, Object> view = settings.publicView(userId);
        view.put("aiProvider", providers.textProvider(settings.apiKeyFor(userId)).name());
        view.put("imageProvider", providers.imageProvider().name());
        return view;
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        Map<String, String> changes = GSON.fromJson(req.getReader(), STRING_MAP);
        if (changes != null) {
            auth.updateDisplayName(userId, changes.get("displayName"));
            settings.update(userId, changes);
        }
        writeJson(resp, view(userId));
    }
}
