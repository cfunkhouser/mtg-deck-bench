package mtgbench.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * The {@code bench.yaml} file as written by users. Every field is optional; command-line flags
 * override it. Relative paths are resolved against the file's directory.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class ConfigFile {
    public BenchConfig.Mode mode;
    public Format format;
    public Integer games;
    public String target;
    public List<Opponent> opponents = new ArrayList<>();
    public Ai ai;
    public Limits limits;
    public Long seed;
    public Integer threads;
    public String output;
    public Boolean checkLegality;
    public Double maxErrorRate;
    public Boolean gameLogs;
    public Double targetCi;
    public Integer maxGames;

    public static class Opponent {
        public String path;
        public Double weight;
    }

    public static class Ai {
        public String profile;
    }

    public static class Limits {
        public Integer maxRounds;
        public Integer gameTimeoutSec;
    }

    private static final ObjectMapper YAML = JsonMapper.builder(new YAMLFactory())
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public static ConfigFile read(Path file) throws IOException {
        ConfigFile c = YAML.readValue(file.toFile(), ConfigFile.class);
        return c != null ? c : new ConfigFile();
    }
}
