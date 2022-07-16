package dev.ayu.latte.config;

import dev.ayu.latte.config.annotations.ConfiguratorIgnore;
import dev.ayu.latte.config.annotations.ConfiguratorDefault;
import dev.ayu.latte.config.annotations.ConfiguratorRename;
import dev.ayu.latte.config.annotations.ConfiguratorRequired;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConfiguratorImpl implements Configurator {

    private static final Map<Class<?>, ConfiguratorAdapter<?>> adapters = new HashMap<>();

    private final Map<String, String> environments = new ConcurrentHashMap<>();
    private boolean allowDefaultToSystemEnvironment = true;

    /**
     * Creates a new {@link ConfiguratorImpl} with the values of
     * the {@link File} specified.
     *
     * @param file The file to read.
     */
    public ConfiguratorImpl(File file) {
        if (!file.exists()) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            reader.lines()
                    .filter(line -> line.contains("=") && !line.startsWith("#"))
                    .map(line -> line.split("=", 2))
                    .forEach(array -> {
                        if (array.length < 2) {
                            environments.put(array[0].toLowerCase(), "");
                            return;
                        }

                        environments.put(array[0].toLowerCase(), array[1]);
                    });
        } catch (IOException e) {
            Util.consoleErrorLog(e);
        }
    }

    @Override
    @Nullable
    public String get(String key) {
        if (environments.containsKey(key.toLowerCase())) {
            return environments.get(key.toLowerCase());
        }

        if (allowDefaultToSystemEnvironment) {
            return System.getenv(key);
        }

        return null;
    }

    @Override
    public Configurator allowDefaultToSystemEnvironment(boolean allow) {
        this.allowDefaultToSystemEnvironment = allow;
        return this;
    }

    @Override
    public void mirror(Class<?> toClass) {
        Arrays.stream(toClass.getDeclaredFields()).forEachOrdered(field -> {
            if (field.isAnnotationPresent(ConfiguratorIgnore.class)) {
                return;
            }

            if (field.trySetAccessible()) {
                String name = field.getName();

                if (field.isAnnotationPresent(ConfiguratorRename.class)) {
                    name = field.getAnnotation(ConfiguratorRename.class).actualKeyName();
                }

                String value = get(name);
                if (value == null && field.isAnnotationPresent(ConfiguratorDefault.class)) {
                    value = field.getAnnotation(ConfiguratorDefault.class).defaultValue();
                } else if (value == null && !field.isAnnotationPresent(ConfiguratorDefault.class)) {
                    Util.consoleErrorLog("Configurator failed to find a value for a field. [field=" + field.getName() + "]");
                }

                try {
                    Class<?> type = field.getType();

                    // Sometimes, you may want this to be null?
                    if (value == null) {
                        if (field.isAnnotationPresent(ConfiguratorRequired.class)) {
                            Util.consoleErrorLog(name + " was not provided during startup!");
                            System.exit(1);
                        } else {
                            field.set(field, null);
                        }
                    } else {
                        if (isTypeEither(type, Boolean.class, boolean.class)) {
                            field.setBoolean(field, Boolean.parseBoolean(value));
                        } else if (isTypeEither(type, Integer.class, int.class)) {
                            field.setInt(field, Integer.parseInt(value));
                        } else if (isTypeEither(type, Long.class, long.class)) {
                            field.setLong(field, Long.parseLong(value));
                        } else if (isTypeEither(type, Double.class, double.class)) {
                            field.setDouble(field, Double.parseDouble(value));
                        } else if (type.equals(String.class)) {
                            field.set(field, value);
                        } else if (adapters.containsKey(type)) {
                            field.set(field, adapters.get(type).transform(name, value, this));
                        } else {
                            Util.consoleErrorLog(new IllegalArgumentException(
                                    "Configurator failed to find a proper transformer or adapter for a field. " +
                                            "Please use ConfiguratorIgnore to ignore otherwise add an adapter via Configurator.addAdapter(...). " +
                                            "[field=" + field.getName() + "]"
                            ));
                            System.exit(1);
                        }
                    }

                } catch (IllegalAccessException | NumberFormatException e) {
                    Util.consoleErrorLog(
                            "Configurator encountered an throwable while attempting to convert a field. " +
                                    "[field=" + field.getName() + "]"
                    );
                    Util.consoleErrorLog(e);
                    System.exit(1);
                }

            } else {
                Util.consoleErrorLog("Configurator failed to set a field accessible. [field=" + field.getName() + "]");
                System.exit(1);
            }
        });
    }

    /**
     * An convenient helper method to identify whether the type specified is
     * either of these two. This is used against generic types which always has two
     * types of classes.
     *
     * @param type    The type to compare against either.
     * @param typeOne The first type to compare.
     * @param typeTwo The second type to compare.
     * @return Is the type matches either of them?
     */
    private boolean isTypeEither(Class<?> type, Class<?> typeOne, Class<?> typeTwo) {
        return type.equals(typeOne) || type.equals(typeTwo);
    }

    /**
     * Adds an adapter that allows {@link  Configurator} to be able to automatically
     * transform specific environment key-pairs into the type it should be.
     *
     * @param clazz   The class that this adapter should transform.
     * @param adapter The adapter to use when transforming the pair into the class.
     */
    public static void addAdapter(Class<?> clazz, ConfiguratorAdapter<?> adapter) {
        adapters.put(clazz, adapter);
    }
}