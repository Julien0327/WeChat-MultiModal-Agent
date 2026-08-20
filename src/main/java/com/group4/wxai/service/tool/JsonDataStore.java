package com.group4.wxai.service.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JsonDataStore {

    private final ObjectMapper objectMapper;
    private final Path directory;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public JsonDataStore(ObjectMapper objectMapper,
                         @Value("${team-tools.data-dir:data/team-tools}") String dataDirectory) {
        this.objectMapper = objectMapper;
        this.directory = Path.of(dataDirectory).normalize();
    }

    public List<Map<String, Object>> load(String file, String userId) {
        synchronized (lock(file)) {
            return new ArrayList<>(loadAll(file).getOrDefault(userId, List.of()));
        }
    }

    public void save(String file, String userId, List<Map<String, Object>> items) {
        synchronized (lock(file)) {
            Map<String, List<Map<String, Object>>> all = loadAll(file);
            all.put(userId, new ArrayList<>(items));
            write(file, all);
        }
    }

    public Map<String, List<Map<String, Object>>> loadAll(String file) {
        synchronized (lock(file)) {
            Path path = directory.resolve(file);
            if (!Files.exists(path)) {
                return new LinkedHashMap<>();
            }
            try {
                return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                        new TypeReference<LinkedHashMap<String, List<Map<String, Object>>>>() { });
            } catch (Exception e) {
                throw new IllegalStateException("无法读取工具数据文件 " + path + "。", e);
            }
        }
    }

    public void write(String file, Map<String, List<Map<String, Object>>> data) {
        synchronized (lock(file)) {
            try {
                Files.createDirectories(directory);
                Path target = directory.resolve(file);
                Path temporary = directory.resolve(file + ".tmp");
                Files.writeString(temporary,
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                        StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new IllegalStateException("无法保存工具数据文件 " + file + "。", e);
            }
        }
    }

    private Object lock(String file) {
        return locks.computeIfAbsent(file, ignored -> new Object());
    }
}
