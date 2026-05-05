package com.koneko.march;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class KonekoMarchConfig {
    public static final int MAX_AUTO_TARGET_ENTRIES = 512;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("konekomarch-client.json");

    private static KonekoMarchConfig INSTANCE;

    public List<String> autoTargetEntityIds = new ArrayList<>(defaultAutoTargetEntityIds());

    public static synchronized KonekoMarchConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static synchronized void save() {
        KonekoMarchConfig config = get();
        config.autoTargetEntityIds = sanitizeAutoTargetIds(config.autoTargetEntityIds);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException ignored) {
        }
    }

    public static synchronized List<String> getAutoTargetEntityIds() {
        KonekoMarchConfig config = get();
        config.autoTargetEntityIds = sanitizeAutoTargetIds(config.autoTargetEntityIds);
        return new ArrayList<>(config.autoTargetEntityIds);
    }

    public static synchronized void setAutoTargetEntityIds(Collection<String> ids) {
        get().autoTargetEntityIds = sanitizeAutoTargetIds(ids);
        save();
    }

    public static synchronized void resetAutoTargetEntityIds() {
        get().autoTargetEntityIds = new ArrayList<>(defaultAutoTargetEntityIds());
        save();
    }

    public static Set<String> sanitizeAutoTargetIdSet(Collection<String> ids) {
        return new LinkedHashSet<>(sanitizeAutoTargetIds(ids));
    }

    public static boolean matchesEntityId(Collection<String> configuredIds, Identifier entityId) {
        if (configuredIds == null || configuredIds.isEmpty() || entityId == null) {
            return false;
        }
        String exact = entityId.toString();
        String namespaceWildcard = entityId.getNamespace() + ":*";
        for (String raw : configuredIds) {
            if (raw == null) {
                continue;
            }
            String entry = raw.trim();
            if (entry.equals(exact) || entry.equals("*") || entry.equals("*:*") || entry.equals(namespaceWildcard)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> defaultAutoTargetEntityIds() {
        return List.of(
                "minecraft:blaze",
                "minecraft:bogged",
                "minecraft:breeze",
                "minecraft:cave_spider",
                "minecraft:creaking",
                "minecraft:creeper",
                "minecraft:drowned",
                "minecraft:elder_guardian",
                "minecraft:ender_dragon",
                "minecraft:endermite",
                "minecraft:evoker",
                "minecraft:ghast",
                "minecraft:guardian",
                "minecraft:hoglin",
                "minecraft:husk",
                "minecraft:illusioner",
                "minecraft:magma_cube",
                "minecraft:phantom",
                "minecraft:piglin_brute",
                "minecraft:pillager",
                "minecraft:ravager",
                "minecraft:shulker",
                "minecraft:silverfish",
                "minecraft:skeleton",
                "minecraft:slime",
                "minecraft:spider",
                "minecraft:stray",
                "minecraft:vex",
                "minecraft:vindicator",
                "minecraft:warden",
                "minecraft:witch",
                "minecraft:wither",
                "minecraft:wither_skeleton",
                "minecraft:zoglin",
                "minecraft:zombie",
                "minecraft:zombie_villager"
        );
    }

    private static KonekoMarchConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                KonekoMarchConfig loaded = GSON.fromJson(reader, KonekoMarchConfig.class);
                if (loaded != null) {
                    loaded.autoTargetEntityIds = sanitizeAutoTargetIds(loaded.autoTargetEntityIds);
                    if (loaded.autoTargetEntityIds.isEmpty()) {
                        loaded.autoTargetEntityIds = new ArrayList<>(defaultAutoTargetEntityIds());
                    }
                    return loaded;
                }
            } catch (IOException | JsonParseException ignored) {
            }
        }
        KonekoMarchConfig config = new KonekoMarchConfig();
        config.autoTargetEntityIds = sanitizeAutoTargetIds(config.autoTargetEntityIds);
        return config;
    }

    private static List<String> sanitizeAutoTargetIds(Collection<String> ids) {
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        if (ids != null) {
            for (String raw : ids) {
                if (raw == null) {
                    continue;
                }
                String value = raw.trim().toLowerCase();
                if (value.isEmpty()) {
                    continue;
                }
                if (value.equals("*") || value.equals("*:*") || value.endsWith(":*")) {
                    cleaned.add(value);
                } else if (Identifier.tryParse(value) != null) {
                    cleaned.add(value);
                }
                if (cleaned.size() >= MAX_AUTO_TARGET_ENTRIES) {
                    break;
                }
            }
        }
        return new ArrayList<>(cleaned);
    }

    private KonekoMarchConfig() {
    }
}
