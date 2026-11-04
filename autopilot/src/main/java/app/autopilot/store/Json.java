package app.autopilot.store;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** JSON columns and tool payloads. Thin wrapper so stores do not each build a mapper. */
@Component
public class Json {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public static Json standalone() {
        return new Json(JsonMapper.builder().build());
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public Map<String, Object> readMap(String json) {
        return json == null ? Map.of() : mapper.readValue(json, MAP);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
