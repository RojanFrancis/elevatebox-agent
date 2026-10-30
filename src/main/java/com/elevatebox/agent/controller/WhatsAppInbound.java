package com.elevatebox.agent.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The incoming messages in a Meta WhatsApp Cloud API webhook.
 *
 * <p>Meta posts {@code {"object": "whatsapp_business_account", "entry": [{"changes": [{"value":
 * {"contacts": [...], "messages": [...], "statuses": [...]}}]}]}}. Only {@code messages} matter
 * here; delivery {@code statuses} are ignored. A message's sender is in {@code from} (digits,
 * no plus sign), its ID in {@code id}, and for text messages the body in {@code text.body}. The
 * sender's WhatsApp profile name is in the matching {@code contacts[].profile.name}.
 */
public final class WhatsAppInbound {

    /**
     * One incoming message.
     *
     * @param id    Meta's message ID, used to ignore repeated deliveries
     * @param phone the sender in E.164, for example {@code +919876543210}
     * @param name  the sender's WhatsApp profile name, or null
     * @param text  the text, or null for a non-text message (image, voice note, ...)
     */
    public record Message(String id, String phone, String name, String text) {}

    private WhatsAppInbound() {}

    /** Parses a webhook body. Throws a RuntimeException if it is not a JSON object. */
    public static List<Message> parse(String payload) {
        JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
        List<Message> messages = new ArrayList<>();
        for (JsonObject entry : objects(root.get("entry"))) {
            for (JsonObject change : objects(entry.get("changes"))) {
                JsonElement value = change.get("value");
                if (value == null || !value.isJsonObject()) {
                    continue;
                }
                JsonObject v = value.getAsJsonObject();
                Map<String, String> names = new HashMap<>();
                for (JsonObject contact : objects(v.get("contacts"))) {
                    JsonElement profile = contact.get("profile");
                    String waId = text(contact, "wa_id");
                    if (waId != null && profile != null && profile.isJsonObject()) {
                        names.put(waId, text(profile.getAsJsonObject(), "name"));
                    }
                }
                for (JsonObject m : objects(v.get("messages"))) {
                    String from = text(m, "from");
                    String id = text(m, "id");
                    if (from == null || id == null) {
                        continue;
                    }
                    JsonElement body = m.get("text");
                    String text = body != null && body.isJsonObject() ? text(body.getAsJsonObject(), "body") : null;
                    messages.add(new Message(id, e164(from), names.get(from), text));
                }
            }
        }
        return List.copyOf(messages);
    }

    static String e164(String waId) {
        String digits = waId.replaceAll("\\D", "");
        return "+" + digits;
    }

    private static List<JsonObject> objects(JsonElement e) {
        List<JsonObject> list = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement item : (JsonArray) e) {
                if (item.isJsonObject()) {
                    list.add(item.getAsJsonObject());
                }
            }
        }
        return list;
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
