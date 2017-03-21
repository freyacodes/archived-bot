/*
 * MIT License
 *
 * Copyright (c) 2017 Frederik Ar. Mikkelsen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package fredboat;

import com.mashape.unirest.http.exceptions.UnirestException;
import fredboat.util.DiscordUtil;
import fredboat.util.DistributionEnum;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);
    
    public static Config CONFIG = null;

    public static String DEFAULT_PREFIX = ";;";
    public static int HIKARI_TIMEOUT_MILLISECONDS = 10000;

    private final DistributionEnum distribution;
    private final String botToken;
    private String oauthSecret;
    private final String jdbcUrl;
    private final int hikariPoolSize;
    private final int numShards;
    private String mashapeKey;
    private String malUser;
    private String malPassword;
    private int scope;
    private List<String> googleKeys = new ArrayList<>();
    private final String[] lavaplayerNodes;
    private final boolean lavaplayerNodesEnabled;
    private String carbonKey;
    private String cbUser;
    private String cbKey;
    private String prefix = DEFAULT_PREFIX;
    private boolean restServerEnabled = true;
    private List<String> adminIds = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public Config(File credentialsFile, File configFile, int scope) {
        try {
            this.scope = scope;
            Yaml yaml = new Yaml();
            String credsFileStr = FileUtils.readFileToString(credentialsFile, "UTF-8");
            String configFileStr = FileUtils.readFileToString(configFile, "UTF-8");
            //remove those pesky tab characters so a potential json file is YAML conform
            credsFileStr = credsFileStr.replaceAll("\t", "");
            configFileStr = configFileStr.replaceAll("\t", "");
            Map<String, Object> creds = (Map<String, Object>) yaml.load(credsFileStr);
            Map<String, Object> config = (Map<String, Object>) yaml.load(configFileStr);
            //avoid null values, rather change them to empty strings
            creds.keySet().forEach((String key) -> creds.putIfAbsent(key, ""));
            config.keySet().forEach((String key) -> config.putIfAbsent(key, ""));


            // Determine distribution
            if ((boolean) config.getOrDefault("patron", false)) {
                distribution = DistributionEnum.PATRON;
            } else if ((boolean) config.getOrDefault("development", false)) {//Determine distribution
                distribution = DistributionEnum.DEVELOPMENT;
            } else {
                distribution = DiscordUtil.isMainBot(this) ? DistributionEnum.MAIN : DistributionEnum.MUSIC;
            }

            log.info("Determined distribution: " + distribution);

            prefix = (String) config.getOrDefault("prefix", prefix);
            restServerEnabled = (boolean) config.getOrDefault("restServerEnabled", restServerEnabled);

            Object admins = config.get("admins");
            if (admins instanceof List) {
                ((List) admins).forEach((Object str) -> adminIds.add(str + ""));
            } else if (admins instanceof String) {
                adminIds.add(admins + "");
            }

            log.info("Using prefix: " + prefix);

            mashapeKey = (String) creds.getOrDefault("mashapeKey", "");
            malUser = (String) creds.getOrDefault("malUser", "");
            malPassword = (String) creds.getOrDefault("malPassword", "");
            carbonKey = (String) creds.getOrDefault("carbonKey", "");
            cbUser = (String) creds.getOrDefault("cbUser", "");
            cbKey = (String) creds.getOrDefault("cbKey", "");
            Map<String, String> token = (Map) creds.get("token");
            if (token != null) {
                botToken = token.getOrDefault(distribution.getId(), "");
            } else botToken = "";


            if (creds.containsKey("oauthSecret")) {
                Map<String, Object> oas = (Map) creds.get("oauthSecret");
                oauthSecret = (String) oas.getOrDefault(distribution.getId(), "");
            }
            jdbcUrl = (String) creds.getOrDefault("jdbcUrl", "");

            Object gkeys = creds.get("googleServerKeys");
            if (gkeys instanceof List) {
                ((List) gkeys).forEach((Object str) -> googleKeys.add((String) str));
            } else if (gkeys instanceof String) {
                googleKeys.add((String) gkeys);
            } else {
                log.warn("No google API keys found. Some commands may not work, check the documentation.");
            }

            List<String> nodesArray = (List) creds.get("lavaplayerNodes");
            if(nodesArray != null) {
                lavaplayerNodesEnabled = true;
                log.info("Using lavaplayer nodes");
                lavaplayerNodes = nodesArray.toArray(new String[nodesArray.size()]);
            } else {
                lavaplayerNodesEnabled = false;
                lavaplayerNodes = new String[0];
                log.info("Not using lavaplayer nodes. Audio playback will be processed locally.");
            }

            if(getDistribution() == DistributionEnum.DEVELOPMENT) {
                log.info("Development distribution; forcing 2 shards");
                numShards = 2;
            } else {
                numShards = DiscordUtil.getRecommendedShardCount(getBotToken());
                log.info("Discord recommends " + numShards + " shard(s)");
            }

            hikariPoolSize = numShards * 2;

            log.info("Hikari max pool size set to " + hikariPoolSize);

        } catch (IOException | UnirestException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Makes sure the requested config file exists in the current format. Will attempt to migrate old formats to new ones
     * old files will be renamed to filename.ext.old to preserve any data
     *
     * @param name relative name of a config file, without the file extension
     * @return a handle on the requested file
     */
    static File loadConfigFile(String name) throws IOException {
        String yamlPath = "./" + name + ".yaml";
        String jsonPath = "./" + name + ".json";
        File yamlFile = new File(yamlPath);
        if (!yamlFile.exists() || yamlFile.isDirectory()) {
            log.warn("Could not find file '" + yamlPath + "', looking for legacy '" + jsonPath + "' to rewrite");
            File json = new File(jsonPath);
            if (!json.exists() || json.isDirectory()) {
                //file is missing
                log.error("No " + name + " file is present. Bot cannot run without it. Check the documentation.");
                throw new FileNotFoundException("Neither '" + yamlPath + "' nor '" + jsonPath + "' present");
            } else {
                //rewrite the json to yaml
                Yaml yaml = new Yaml();
                String fileStr = FileUtils.readFileToString(json, "UTF-8");
                //remove tab character from json file to make it a valid YAML file
                fileStr = fileStr.replaceAll("\t", "");
                @SuppressWarnings("unchecked")
                Map<String, Object> configFile = (Map) yaml.load(fileStr);
                yaml.dump(configFile, new FileWriter(yamlFile));
                Files.move(Paths.get(jsonPath), Paths.get(jsonPath + ".old"), REPLACE_EXISTING);
                log.info("Migrated file '" + jsonPath + "' to '" + yamlPath + "'");
            }
        }

        return yamlFile;
    }

    public String getRandomGoogleKey() {
        return getGoogleKeys().get((int) Math.floor(Math.random() * getGoogleKeys().size()));
    }

    public DistributionEnum getDistribution() {
        return distribution;
    }

    String getBotToken() {
        return botToken;
    }

    String getOauthSecret() {
        return oauthSecret;
    }

    String getJdbcUrl() {
        return jdbcUrl;
    }

    public int getHikariPoolSize() {
        return hikariPoolSize;
    }

    public int getNumShards() {
        return numShards;
    }

    public String getMashapeKey() {
        return mashapeKey;
    }

    public String getMalUser() {
        return malUser;
    }

    public String getMalPassword() {
        return malPassword;
    }

    public int getScope() {
        return scope;
    }

    public List<String> getGoogleKeys() {
        return googleKeys;
    }

    public String[] getLavaplayerNodes() {
        return lavaplayerNodes;
    }

    public boolean isLavaplayerNodesEnabled() {
        return lavaplayerNodesEnabled;
    }

    public String getCarbonKey() {
        return carbonKey;
    }

    public String getCbUser() {
        return cbUser;
    }

    public String getCbKey() {
        return cbKey;
    }

    public String getPrefix() {
        return prefix;
    }

    public boolean isRestServerEnabled() {
        return restServerEnabled;
    }

    public List<String> getAdminIds() {
        return adminIds;
    }
}
