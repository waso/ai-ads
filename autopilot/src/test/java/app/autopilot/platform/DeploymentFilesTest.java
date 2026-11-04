package app.autopilot.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The deployment files must parse, keep secrets out of literals, and only set variables the application reads. */
class DeploymentFilesTest {

    private static final Pattern SECRETISH = Pattern.compile("(?i)(secret|password|token)");

    private static List<Map<String, Object>> docs(String file) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object d : new Yaml().loadAll(Files.readString(Path.of(file)))) {
            if (d instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) m;
                out.add(cast);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private static Set<String> variablesTheApplicationReads() throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher m = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]").matcher(Files.readString(Path.of("src/main/resources/application.yml")));
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static List<Path> manifests() throws IOException {
        try (Stream<Path> k8s = Files.list(Path.of("deploy/k8s")); Stream<Path> run = Files.list(Path.of("deploy/cloudrun"))) {
            return Stream.concat(k8s, run).filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
    }

    @Test
    @DisplayName("[AC-37] the Dockerfile builds a non-root image and bakes in no credentials")
    void dockerfile() throws IOException {
        String d = Files.readString(Path.of("Dockerfile"));
        assertThat(d).contains("eclipse-temurin:21").contains("USER 10001").contains("ENTRYPOINT");
        assertThat(d).doesNotContainIgnoringCase("password=").doesNotContain("COPY .env").doesNotContain("credentials.json");
        assertThat(Files.readString(Path.of(".dockerignore"))).contains("target");
    }

    @Test
    @DisplayName("[AC-37] every Kubernetes and Cloud Run file parses as YAML")
    void yamlParses() throws IOException {
        List<Path> files = manifests();
        assertThat(files).extracting(p -> p.getFileName().toString()).contains("deployment.yaml", "cronjob-watchdog.yaml", "configmap.yaml",
                "serviceaccount.yaml", "secrets.example.yaml", "service.yaml", "job.yaml");
        for (Path f : files) assertThat(docs(f.toString())).as(f.toString()).isNotEmpty();
    }

    @Test
    @DisplayName("[AC-37] the Deployment and the watchdog CronJob run non-root with no privileges and a read-only filesystem")
    void hardenedPods() throws IOException {
        List<Map<String, Object>> all = new ArrayList<>(docs("deploy/k8s/deployment.yaml"));
        all.addAll(docs("deploy/k8s/cronjob-watchdog.yaml"));
        int containers = 0;
        for (Map<String, Object> d : all) {
            Map<String, Object> podSpec = podSpec(d);
            if (podSpec == null) continue;
            assertThat(map(podSpec.get("securityContext"))).containsEntry("runAsNonRoot", true);
            for (Map<String, Object> c : list(podSpec.get("containers"))) {
                containers++;
                Map<String, Object> sc = map(c.get("securityContext"));
                assertThat(sc).as("container %s", c.get("name")).containsEntry("allowPrivilegeEscalation", false).containsEntry("readOnlyRootFilesystem", true);
                assertThat(sc).doesNotContainEntry("privileged", true);
            }
        }
        assertThat(containers).isGreaterThanOrEqualTo(4);
        Map<String, Object> app = list(podSpec(docs("deploy/k8s/deployment.yaml").get(0)).get("containers")).get(0);
        assertThat(app).containsKeys("readinessProbe", "livenessProbe");
    }

    private static Map<String, Object> podSpec(Map<String, Object> d) {
        Object kind = d.get("kind");
        if ("Deployment".equals(kind)) return map(map(map(d.get("spec")).get("template")).get("spec"));
        if ("CronJob".equals(kind)) return map(map(map(map(map(d.get("spec")).get("jobTemplate")).get("spec")).get("template")).get("spec"));
        return null;
    }

    @Test
    @DisplayName("[AC-37] the watchdog runs every 15 minutes as a one-shot job that cannot overlap itself")
    void watchdogSchedule() throws IOException {
        Map<String, Object> cron = docs("deploy/k8s/cronjob-watchdog.yaml").get(0);
        Map<String, Object> spec = map(cron.get("spec"));
        assertThat(spec).containsEntry("schedule", "*/15 * * * *").containsEntry("concurrencyPolicy", "Forbid");
        Map<String, Object> container = list(podSpec(cron).get("containers")).get(0);
        assertThat(list(container.get("env"))).anySatisfy(e -> {
            assertThat(e).containsEntry("name", "SPRING_PROFILES_ACTIVE").containsEntry("value", "watchdog");
        });
        String job = Files.readString(Path.of("deploy/cloudrun/job.yaml"));
        assertThat(job).contains("SPRING_PROFILES_ACTIVE").contains("watchdog").contains("*/15 * * * *").contains("maxRetries: 0");
    }

    @Test
    @DisplayName("[AC-37] no secret is a literal value in a manifest; secrets come from secret references only")
    void noLiteralSecrets() throws IOException {
        for (Path f : manifests()) {
            if (f.getFileName().toString().equals("secrets.example.yaml")) continue;
            for (Map<String, Object> d : docs(f.toString())) {
                forEachEnv(d, env -> {
                    String name = String.valueOf(env.get("name"));
                    if (SECRETISH.matcher(name).find()) {
                        assertThat(env).as("%s in %s must come from a secret", name, f).containsKey("valueFrom").doesNotContainKey("value");
                    }
                });
                if ("ConfigMap".equals(d.get("kind"))) {
                    for (String key : map(d.get("data")).keySet()) {
                        assertThat(SECRETISH.matcher(key).find()).as("ConfigMap key %s looks like a secret", key).isFalse();
                    }
                }
            }
        }
        Map<String, Object> secret = docs("deploy/k8s/secrets.example.yaml").get(0);
        assertThat(map(secret.get("stringData")).values()).allSatisfy(v -> assertThat(v.toString()).startsWith("CHANGE_ME"));
    }

    @SuppressWarnings("unchecked")
    private static void forEachEnv(Object node, java.util.function.Consumer<Map<String, Object>> visit) {
        if (node instanceof Map<?, ?> m) {
            Object env = m.get("env");
            if (env instanceof List<?> l) for (Object e : l) if (e instanceof Map<?, ?> em) visit.accept((Map<String, Object>) em);
            m.values().forEach(v -> forEachEnv(v, visit));
        } else if (node instanceof List<?> l) {
            l.forEach(v -> forEachEnv(v, visit));
        }
    }

    @Test
    @DisplayName("[AC-37] the manifests only set variables the application reads, and start in read-only mode")
    void manifestsMatchTheApplication() throws IOException {
        Set<String> known = variablesTheApplicationReads();
        known.add("SPRING_PROFILES_ACTIVE");
        Set<String> used = new TreeSet<>();
        for (Path f : manifests()) {
            for (Map<String, Object> d : docs(f.toString())) {
                forEachEnv(d, env -> used.add(String.valueOf(env.get("name"))));
                if ("ConfigMap".equals(d.get("kind"))) used.addAll(map(d.get("data")).keySet());
                if ("Secret".equals(d.get("kind"))) used.addAll(map(d.get("stringData")).keySet());
            }
        }
        assertThat(used).as("variables set in manifests but not read by application.yml").isSubsetOf(known);

        Map<String, Object> cm = map(docs("deploy/k8s/configmap.yaml").get(0).get("data"));
        assertThat(cm).containsEntry("AUTONOMY_LEVEL", "L0").containsKeys("MONTHLY_CAP_MICROS", "OWNER_EMAIL", "ADS_CUSTOMER_ID");
    }

    @Test
    @DisplayName("[AC-37] the README says how to run, configure, deploy and operate it, and what is not verified")
    void readme() throws IOException {
        String r = Files.readString(Path.of("README.md"));
        assertThat(r).contains("## Configure", "## Build and test", "## Deploy", "## Operate", "kill switch", "Not verified here",
                "authorization server", "never talked to Google", "mvn verify");
        assertThat(Files.readString(Path.of("deploy/sql/roles.sql"))).contains("autopilot_app").contains("autopilot_owner");
    }
}
