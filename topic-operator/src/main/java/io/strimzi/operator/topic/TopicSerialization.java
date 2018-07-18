/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.strimzi.api.kafka.model.TopicBuilder;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.String.format;

/**
 * Serialization of a {@link }Topic} to and from various other representations.
 */
public class TopicSerialization {

    // These are the keys in the ConfigMap data
    public static final String CM_KEY_PARTITIONS = "partitions";
    public static final String CM_KEY_REPLICAS = "replicas";
    public static final String CM_KEY_NAME = "name";
    public static final String CM_KEY_CONFIG = "config";

    // These are the keys in the JSON we store in ZK
    public static final String JSON_KEY_TOPIC_NAME = "topic-name";
    public static final String JSON_KEY_MAP_NAME = "map-name";
    public static final String JSON_KEY_PARTITIONS = "partitions";
    public static final String JSON_KEY_REPLICAS = "replicas";
    public static final String JSON_KEY_CONFIG = "config";

    @SuppressWarnings("unchecked")
    private static Map<String, String> topicConfigFromTopicConfig(io.strimzi.api.kafka.model.Topic topic) {
        Map<String, String> result = new HashMap<>(topic.getConfig().size());
        for (Map.Entry<String, Object> entry : topic.getConfig().entrySet()) {
            String key = entry.getKey();
            Object v = entry.getValue();
            if (v instanceof String
                    || v instanceof Long
                    || v instanceof Double
                    || v instanceof Boolean) {
                result.put(key, v.toString());
            } else {
                String msg = "The value corresponding to the key must have a string, number or boolean value";
                if (v == null) {
                    msg += " but the value was null";
                } else {
                    msg += " but was of type " + v.getClass().getName();
                }
                throw new InvalidTopicException(topic, "Topic's 'config' section has invalid entry: " +
                        "The key '" + key + "' of the topic config is invalid: " + msg);
            }
        }
        return result;
    }


    /**
     * Create a Topic to reflect the given ConfigMap.
     * @throws InvalidTopicException
     */
    public static Topic fromConfigMap(ConfigMap cm) {
        return null;
    }
    public static Topic fromTopicResource(io.strimzi.api.kafka.model.Topic topic) {
        if (topic == null) {
            return null;
        }
        Topic.Builder builder = new Topic.Builder()
                .withMapName(topic.getMetadata().getName())
                .withTopicName(getTopicName(topic))
                .withNumPartitions(getPartitions(topic))
                .withNumReplicas(getReplicas(topic))
                .withConfig(topicConfigFromTopicConfig(topic));
        return builder.build();
    }

    private static String getTopicName(io.strimzi.api.kafka.model.Topic topic) {
        String prefix = "Topics's 'topicName' property is invalid as a topic name: ";
        String topicName = topic.getTopicName();
        if (topicName == null) {
            topicName = topic.getMetadata().getName();
            prefix = "Topics's 'topicName' property is absent and Topics's metadata.name is invalid as a topic name: ";
        }
        try {
            org.apache.kafka.common.internals.Topic.validate(topicName);
        } catch (org.apache.kafka.common.errors.InvalidTopicException e) {
            throw new InvalidTopicException(topic, prefix + e.getMessage());
        }
        return topicName;
    }

    private static short getReplicas(io.strimzi.api.kafka.model.Topic topic) {
        int replicas = topic.getReplicas();
        if (replicas < 1 || replicas > Short.MAX_VALUE) {
            throw new InvalidTopicException(topic, "Topic's replicas should be between 1 and " + Short.MAX_VALUE + " inclusive");
        }
        return (short) replicas;
    }

    private static int getPartitions(io.strimzi.api.kafka.model.Topic topic) {
        int partitions = topic.getPartitions();
        if (partitions < 1) {
            throw new InvalidTopicException(topic, "Topic's partitions should be strictly greater than 0");
        }
        return partitions;
    }

    public static ConfigMap toConfigMap(Topic topic, LabelPredicate cmPredicate) {
        return null;
    }

    /**
     * Create a ConfigMap to reflect the given Topic.
     */
    public static io.strimzi.api.kafka.model.Topic toTopicResource(Topic topic, LabelPredicate cmPredicate) {
        MapName mapName = topic.getOrAsMapName();
        return new TopicBuilder().withApiVersion("v1")
                    .withMetadata(new ObjectMetaBuilder()
                    .withName(mapName.toString())
                    .withLabels(cmPredicate.labels()).build())
                    // TODO .withUid()

                .withTopicName(topic.getTopicName().toString())
                .withPartitions(topic.getNumPartitions())
                .withReplicas(topic.getNumReplicas())
                .withConfig(new LinkedHashMap<>(topic.getConfig()))
                .build();
    }


    /**
     * Create a NewTopic to reflect the given Topic.
     */
    public static NewTopic toNewTopic(Topic topic, Map<Integer, List<Integer>> assignment) {
        NewTopic newTopic;
        if (assignment != null) {
            if (topic.getNumPartitions() != assignment.size()) {
                throw new IllegalArgumentException(
                        format("Topic %s has %d partitions supplied, but the number of partitions " +
                                        "configured in ConfigMap %s is %d",
                                topic.getTopicName(), assignment.size(), topic.getMapName(), topic.getNumPartitions()));
            }
            for (int partition = 0; partition < assignment.size(); partition++) {
                final List<Integer> value = assignment.get(partition);
                if (topic.getNumReplicas() != value.size()) {
                    throw new IllegalArgumentException(
                            format("Partition %d of topic %s has %d assigned replicas, " +
                                    "but the number of replicas configured in ConfigMap %s for the topic is %d",
                                    partition, topic.getTopicName(), value.size(), topic.getMapName(), topic.getNumReplicas()));
                }
            }
            newTopic = new NewTopic(topic.getTopicName().toString(), assignment);
        } else {
            newTopic = new NewTopic(topic.getTopicName().toString(), topic.getNumPartitions(), topic.getNumReplicas());
        }

        newTopic.configs(topic.getConfig());
        return newTopic;
    }

    /**
     * Return a singleton map from the topic {@link ConfigResource} for the given topic,
     * to the {@link Config} of the given topic.
     */
    public static Map<ConfigResource, Config> toTopicConfig(Topic topic) {
        Set<ConfigEntry> configEntries = new HashSet<>();
        for (Map.Entry<String, String> entry : topic.getConfig().entrySet()) {
            configEntries.add(new ConfigEntry(entry.getKey(), entry.getValue()));
        }
        Config config = new Config(configEntries);
        return Collections.singletonMap(
                new ConfigResource(ConfigResource.Type.TOPIC, topic.getTopicName().toString()),
                config);
    }

    /**
     * Create a Topic to reflect the given TopicMetadata.
     */
    public static Topic fromTopicMetadata(TopicMetadata meta) {
        if (meta == null) {
            return null;
        }
        Topic.Builder builder = new Topic.Builder()
                .withTopicName(meta.getDescription().name())
                .withNumPartitions(meta.getDescription().partitions().size())
                .withNumReplicas((short) meta.getDescription().partitions().get(0).replicas().size());
        for (ConfigEntry entry: meta.getConfig().entries()) {
            if (!entry.isDefault()) {
                builder.withConfigEntry(entry.name(), entry.value());
            }
        }
        return builder.build();
    }

    /**
     * Returns the UTF-8 encoded JSON to reflect the given Topic.
     * This is what is stored in the znodes owned by the {@link ZkTopicStore}.
     */
    public static byte[] toJson(Topic topic) {
        ObjectMapper mapper = objectMapper();
        ObjectNode root = mapper.createObjectNode();
        // TODO Do we store the k8s uid here?
        root.put(JSON_KEY_MAP_NAME, topic.getOrAsMapName().toString());
        root.put(JSON_KEY_TOPIC_NAME, topic.getTopicName().toString());
        root.put(JSON_KEY_PARTITIONS, topic.getNumPartitions());
        root.put(JSON_KEY_REPLICAS, topic.getNumReplicas());

        ObjectNode config = mapper.createObjectNode();
        for (Map.Entry<String, String> entry : topic.getConfig().entrySet()) {
            config.put(entry.getKey(), entry.getValue());
        }
        root.set(JSON_KEY_CONFIG, config);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            mapper.writeValue(baos, root);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    /**
     * Returns the Topic represented by the given UTF-8 encoded JSON.
     * This is what is stored in the znodes owned by the {@link ZkTopicStore}.
     */
    @SuppressWarnings("unchecked")
    public static Topic fromJson(byte[] json) {
        ObjectMapper mapper = objectMapper();
        Map<String, Object> root = null;
        try {
            root = mapper.readValue(json, Map.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Topic.Builder builder = new Topic.Builder();
        builder.withTopicName((String) root.get(JSON_KEY_TOPIC_NAME))
                .withMapName((String) root.get(JSON_KEY_MAP_NAME))
                .withNumPartitions((Integer) root.get(JSON_KEY_PARTITIONS))
                .withNumReplicas(((Integer) root.get(JSON_KEY_REPLICAS)).shortValue());
        Map<String, String> config = (Map) root.get(JSON_KEY_CONFIG);
        for (Map.Entry<String, String> entry : config.entrySet()) {
            builder.withConfigEntry(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private static ObjectMapper objectMapper() {
        JsonFactory jf = new JsonFactory();
        jf.configure(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION, false);
        return new ObjectMapper(jf);
    }

}
