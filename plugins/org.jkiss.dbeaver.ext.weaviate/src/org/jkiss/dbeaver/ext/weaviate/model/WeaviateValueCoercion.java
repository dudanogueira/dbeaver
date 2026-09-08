/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.weaviate.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.weaviate.client6.v1.api.collections.DataType;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.exec.DBCException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a value typed into the grid into the value the server expects for that property.
 * <p>
 * The write-side twin of {@link WeaviateRowMapper}, and the part of row editing that is actually
 * hard: the read path can render anything as text, while the write path has to put a
 * <em>correctly typed</em> JSON value back, and Weaviate refuses a mismatch outright --
 * <em>invalid text property 'title': not a string, but json.Number</em>.
 * <p>
 * <b>Every value is coerced by the property's declared type, never by what it looks like.</b> That
 * is what keeps auto-schema out of the picture. With auto-schema on -- the default -- a write is
 * allowed to change the schema: sending a property the collection does not declare creates it,
 * and creates it with whatever type the JSON happened to have. Measured against 1.39, an insert
 * carrying {@code surprise: 42} left behind a permanent {@code number} property, and
 * {@code "2026-01-02T03:04:05Z"} left behind a {@code date} one. Property types cannot be changed
 * afterwards, so a single mistyped write is a one-way door.
 * <p>
 * Two rules follow, and they are the reason this class exists rather than a handful of
 * {@code toString()} calls:
 * <ul>
 * <li>a value goes out as the type the schema declares, or the write is refused here with the
 *     column named -- better than the server's message, which names the JSON type rather than
 *     what the person typed;</li>
 * <li>a column that is not a declared property never reaches the server at all. The grid also
 *     shows {@code _score}, {@code _distance}, {@code _creationTime} and the vector columns, and
 *     sending one of those as a property is exactly how a collection would silently acquire a
 *     permanent {@code _score} field.</li>
 * </ul>
 */
public final class WeaviateValueCoercion {

    /** What Weaviate hands back and expects for a date, and what it stores. */
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private WeaviateValueCoercion() {
    }

    /**
     * Convert one grid value for a property of {@code weaviateType}.
     *
     * @param value       what the grid holds; null means the property is left out of the request
     * @param weaviateType the property's declared type, e.g. {@code text} or {@code int[]}
     * @param column      the column name, for the message when the value cannot be converted
     * @return the value to put in the properties map, or null to omit the property
     * @throws DBCException when the value cannot be the declared type, naming the column and what
     *                      was typed -- a refusal here is cheaper and clearer than the server's
     */
    @Nullable
    public static Object toWireValue(
        @Nullable Object value, @NotNull String weaviateType, @NotNull String column
    ) throws DBCException {
        if (value == null) {
            return null;
        }
        String text = value instanceof String s ? s.strip() : null;
        // An emptied cell is an absent value, not an empty one -- except for text, where "" is a
        // value someone may genuinely want. Weaviate stores it and reads it back.
        if (text != null && text.isEmpty() && !DataType.TEXT.equals(weaviateType)) {
            return null;
        }
        try {
            return convert(value, text, weaviateType, column);
        } catch (DBCException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DBCException(
                "Cannot store " + describe(value) + " in " + column
                    + ", which is " + weaviateType + ": " + e.getMessage(), e);
        }
    }

    @Nullable
    private static Object convert(
        @NotNull Object value, @Nullable String text, @NotNull String type, @NotNull String column
    ) throws DBCException {
        return switch (type) {
            case DataType.TEXT, DataType.BLOB, DataType.BLOB_HASH -> text != null ? text : String.valueOf(value);
            case DataType.UUID -> uuid(text != null ? text : String.valueOf(value), column);
            case DataType.INT -> asLong(value, text, column);
            case DataType.NUMBER -> asDouble(value, text, column);
            case DataType.BOOL -> asBoolean(value, text, column);
            case DataType.DATE -> asDate(value, text, column);
            case DataType.PHONE_NUMBER -> phone(value, text, column);
            case DataType.GEO_COORDINATES -> geo(value, text, column);
            case DataType.OBJECT -> json(text, column, false);
            case DataType.TEXT_ARRAY, DataType.UUID_ARRAY, DataType.INT_ARRAY,
                 DataType.NUMBER_ARRAY, DataType.BOOL_ARRAY, DataType.DATE_ARRAY,
                 DataType.OBJECT_ARRAY -> array(value, text, type, column);
            // A type this build has never heard of. Passing the text through is the only honest
            // move: refusing would block a property a newer server understands perfectly well.
            default -> text != null ? text : value;
        };
    }

    @NotNull
    private static Object asLong(
        @NotNull Object value, @Nullable String text, @NotNull String column
    ) throws DBCException {
        if (value instanceof Number n) {
            if (n.doubleValue() != Math.floor(n.doubleValue())) {
                throw new DBCException(column + " is an int, but " + n + " has a fractional part");
            }
            return n.longValue();
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new DBCException(column + " is an int, and \"" + text + "\" is not a whole number");
        }
    }

    @NotNull
    private static Object asDouble(
        @NotNull Object value, @Nullable String text, @NotNull String column
    ) throws DBCException {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new DBCException(column + " is a number, and \"" + text + "\" is not one");
        }
    }

    @NotNull
    private static Object asBoolean(
        @NotNull Object value, @Nullable String text, @NotNull String column
    ) throws DBCException {
        if (value instanceof Boolean b) {
            return b;
        }
        String lower = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(lower) || "false".equals(lower)) {
            return Boolean.parseBoolean(lower);
        }
        // Deliberately not accepting 1/0 or yes/no: a boolean column that quietly takes "1" is a
        // boolean column someone will one day put "2" into.
        throw new DBCException(column + " is a boolean, and \"" + text + "\" is neither true nor false");
    }

    /**
     * Dates go out as RFC 3339 with an offset, which is the only form Weaviate stores.
     * <p>
     * A date typed without one is read as UTC rather than as local time. Guessing the machine's
     * zone would make the same keystrokes mean different instants on two laptops, and a stored
     * timestamp that moved between developers is worse than one that is explicit about assuming
     * UTC.
     */
    @NotNull
    private static Object asDate(
        @NotNull Object value, @Nullable String text, @NotNull String column
    ) throws DBCException {
        if (value instanceof Date date) {
            return RFC3339.format(date.toInstant().atOffset(ZoneOffset.UTC));
        }
        if (value instanceof Instant instant) {
            return RFC3339.format(instant.atOffset(ZoneOffset.UTC));
        }
        if (value instanceof OffsetDateTime odt) {
            return RFC3339.format(odt);
        }
        String raw = text == null ? String.valueOf(value) : text;
        try {
            return RFC3339.format(OffsetDateTime.parse(raw));
        } catch (DateTimeParseException ignored) {
            // fall through to the no-offset forms
        }
        for (DateTimeFormatter f : List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME, DateTimeFormatter.ISO_LOCAL_DATE)) {
            try {
                java.time.temporal.TemporalAccessor parsed = f.parse(raw);
                java.time.LocalDate day = java.time.LocalDate.from(parsed);
                java.time.LocalTime time = f == DateTimeFormatter.ISO_LOCAL_DATE
                    ? java.time.LocalTime.MIDNIGHT
                    : java.time.LocalTime.from(parsed);
                return RFC3339.format(day.atTime(time).atOffset(ZoneOffset.UTC));
            } catch (java.time.DateTimeException ignored) {
                // try the next shape
            }
        }
        throw new DBCException(
            column + " is a date, and \"" + raw + "\" is not one. Weaviate stores RFC 3339, "
                + "for instance 2026-09-08T14:30:00Z");
    }

    @NotNull
    private static Object uuid(@NotNull String text, @NotNull String column) throws DBCException {
        try {
            return java.util.UUID.fromString(text).toString();
        } catch (IllegalArgumentException e) {
            throw new DBCException(column + " is a uuid, and \"" + text + "\" is not one");
        }
    }

    /**
     * A phone number is an object to Weaviate, not a string: it wants {@code input}, and parses the
     * rest itself. Typing the number alone is the ordinary case, so a bare string is accepted and
     * wrapped; a JSON object is passed through for anyone who needs {@code defaultCountry}.
     */
    @NotNull
    private static Object phone(
        @NotNull Object value, @Nullable String text, @NotNull String column
    ) throws DBCException {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        String raw = text == null ? String.valueOf(value) : text;
        if (raw.startsWith("{")) {
            return json(raw, column, false);
        }
        Map<String, Object> phone = new LinkedHashMap<>();
        phone.put("input", raw);
        return phone;
    }

    /** Geo is an object too. {@code "-22.9, -43.2"} is accepted as well as the JSON form. */
    @NotNull
    private static Object geo(
        @NotNull Object value, @Nullable String text, @NotNull String column
    ) throws DBCException {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        String raw = text == null ? String.valueOf(value) : text;
        if (raw.startsWith("{")) {
            return json(raw, column, false);
        }
        String[] parts = raw.split(",");
        if (parts.length != 2) {
            throw new DBCException(
                column + " is a geo coordinate; type \"latitude, longitude\" or a JSON object");
        }
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("latitude", asDouble(parts[0].strip(), parts[0].strip(), column));
        geo.put("longitude", asDouble(parts[1].strip(), parts[1].strip(), column));
        return geo;
    }

    /**
     * Arrays are typed through their element type, so a text array of numbers is refused here
     * rather than by the server.
     */
    @NotNull
    private static Object array(
        @NotNull Object value, @Nullable String text, @NotNull String type, @NotNull String column
    ) throws DBCException {
        String element = type.endsWith("[]") ? type.substring(0, type.length() - 2) : type;
        List<Object> out = new ArrayList<>();
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                out.add(toWireValue(item, element, column));
            }
            return out;
        }
        String raw = text == null ? String.valueOf(value) : text;
        Object parsed = json(raw, column, true);
        for (Object item : (List<?>) parsed) {
            out.add(toWireValue(item, element, column));
        }
        return out;
    }

    @NotNull
    private static Object json(
        @Nullable String text, @NotNull String column, boolean wantArray
    ) throws DBCException {
        if (text == null) {
            throw new DBCException(column + " needs JSON");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new DBCException(column + " is not valid JSON: " + text);
        }
        if (wantArray && !parsed.isJsonArray()) {
            throw new DBCException(column + " is a list; type it as JSON, for instance [\"a\", \"b\"]");
        }
        if (!wantArray && !parsed.isJsonObject()) {
            throw new DBCException(column + " is an object; type it as JSON");
        }
        return plain(parsed);
    }

    /** Gson types must not cross into the request; the client serializes plain values itself. */
    @Nullable
    private static Object plain(@NotNull JsonElement element) {
        if (element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            element.getAsJsonArray().forEach(child -> list.add(plain(child)));
            return list;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            element.getAsJsonObject().entrySet()
                .forEach(e -> map.put(e.getKey(), plain(e.getValue())));
            return map;
        }
        var primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            double d = primitive.getAsDouble();
            return d == Math.floor(d) && !Double.isInfinite(d) ? (Object) (long) d : (Object) d;
        }
        return primitive.getAsString();
    }

    @NotNull
    private static String describe(@NotNull Object value) {
        String text = String.valueOf(value);
        return text.length() > 40 ? "\"" + text.substring(0, 40) + "…\"" : "\"" + text + "\"";
    }
}
