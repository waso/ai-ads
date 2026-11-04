package app.autopilot.ads.google;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens a {@code GoogleAdsRow} to a map keyed by the selected field paths of a query
 * ({@code campaign.id}, {@code metrics.cost_micros}, ...). Enums become their names, 64-bit numbers stay
 * longs, repeated fields become lists and a field that is not set becomes null.
 */
final class ReportRows {

    private ReportRows() {}

    static Map<String, Object> flatten(Message row, List<String> fieldPaths) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String path : fieldPaths) {
            out.put(path, resolve(row, path.split("\\.")));
        }
        return out;
    }

    private static Object resolve(Message message, String[] parts) {
        Message current = message;
        for (int i = 0; i < parts.length; i++) {
            FieldDescriptor field = current.getDescriptorForType().findFieldByName(parts[i]);
            if (field == null) {
                return null;
            }
            boolean last = i == parts.length - 1;
            if (field.isRepeated()) {
                List<?> items = (List<?>) current.getField(field);
                if (last) {
                    return items.stream().map(ReportRows::convert).toList();
                }
                String[] rest = Arrays.copyOfRange(parts, i + 1, parts.length);
                List<Object> out = new ArrayList<>();
                for (Object item : items) {
                    out.add(item instanceof Message m ? resolve(m, rest) : null);
                }
                return out;
            }
            if (field.hasPresence() && !current.hasField(field)) {
                return null;
            }
            Object value = current.getField(field);
            if (last) {
                return convert(value);
            }
            if (!(value instanceof Message next)) {
                return null;
            }
            current = next;
        }
        return null;
    }

    static Object convert(Object value) {
        if (value instanceof EnumValueDescriptor e) {
            return e.getName();
        }
        if (value instanceof ByteString b) {
            return Base64.getEncoder().encodeToString(b.toByteArray());
        }
        if (value instanceof Message m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<FieldDescriptor, Object> entry : m.getAllFields().entrySet()) {
                out.put(entry.getKey().getName(), convert(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ReportRows::convert).toList();
        }
        return value;
    }
}
