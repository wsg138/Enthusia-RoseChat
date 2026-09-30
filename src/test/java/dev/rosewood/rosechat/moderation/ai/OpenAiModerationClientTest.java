package dev.rosewood.rosechat.moderation.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class OpenAiModerationClientTest {
    @Test
    void parsesTargetAndContextResultsSeparately() {
        OpenAiModerationClient client = new OpenAiModerationClient(
                HttpClient.newHttpClient(),
                new Gson(),
                AiModerationTestConfig.create(),
                "test-key"
        );
        String json = """
                {"results":[
                  {"flagged":false,"categories":{"violence":true},"category_scores":{"violence":0.87}},
                  {"flagged":true,"categories":{"harassment/threatening":true},"category_scores":{"harassment/threatening":0.91}}
                ]}
                """;

        OpenAiModerationClient.BatchResult result = client.parseBody(json);

        assertEquals(0.87, result.target().score("violence"), 0.0001);
        assertTrue(result.context().flagged());
        assertEquals(0.91, result.context().score("harassment/threatening"), 0.0001);
    }
}
