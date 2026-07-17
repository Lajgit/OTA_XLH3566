package com.zeda.ota.gameconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class GameConfigCanonicalizerTest {

    @Test
    public void standardVectorsShouldMatch() throws Exception {
        InputStream input = getClass().getResourceAsStream(
                "/game_config_hash_vectors.json"
        );
        assertNotNull(input);

        JsonObject root;
        try (InputStreamReader reader = new InputStreamReader(
                input,
                StandardCharsets.UTF_8
        )) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        JsonArray vectors = root.getAsJsonArray("vectors");
        assertEquals(8, vectors.size());

        for (int index = 0; index < vectors.size(); index++) {
            JsonObject vector = vectors.get(index).getAsJsonObject();
            String id = vector.get("id").getAsString();
            String inputJson = vector.get("inputJson").getAsString();
            String expectedCanonical = vector.get("canonicalJson").getAsString();
            String expectedHash = vector.get("sha256").getAsString();

            String actualCanonical = GameConfigCanonicalizer.canonicalize(
                    JsonParser.parseString(inputJson)
            );
            assertEquals(id, expectedCanonical, actualCanonical);
            assertEquals(
                    id,
                    expectedHash,
                    GameConfigCanonicalizer.sha256(actualCanonical)
            );
            assertEquals(
                    id,
                    expectedHash,
                    GameConfigCanonicalizer.calculateConfigHash(
                            JsonParser.parseString(inputJson)
                    )
            );
        }
    }

    @Test
    public void objectKeysShouldUseUnicodeCodePointOrder() {
        JsonObject object = new JsonObject();
        object.addProperty("😀", 1);
        object.addProperty("\ue000", 2);

        assertEquals(
                "{\"\":2,\"😀\":1}",
                GameConfigCanonicalizer.canonicalize(object)
        );
    }

    @Test
    public void arrayOrderShouldChangeHash() {
        String first = GameConfigCanonicalizer.calculateConfigHash(
                JsonParser.parseString("{\"values\":[1,2,3]}")
        );
        String second = GameConfigCanonicalizer.calculateConfigHash(
                JsonParser.parseString("{\"values\":[3,2,1]}")
        );

        assertNotEquals(first, second);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidNumberShouldBeRejected() {
        GameConfigCanonicalizer.canonicalize(
                new JsonPrimitive(Double.NaN)
        );
    }
}
