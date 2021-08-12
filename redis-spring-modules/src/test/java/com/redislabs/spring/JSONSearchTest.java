package com.redislabs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

import javax.annotation.PreDestroy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import com.redislabs.spring.ops.RedisModulesOperations;
import com.redislabs.spring.ops.json.JSONOperations;
import com.redislabs.spring.ops.search.SearchOperations;

import io.redisearch.AggregationResult;
import io.redisearch.Document;
import io.redisearch.FieldName;
import io.redisearch.Query;
import io.redisearch.Schema;
import io.redisearch.SearchResult;
import io.redisearch.Schema.Field;
import io.redisearch.Schema.FieldType;
import io.redisearch.Schema.TextField;
import io.redisearch.aggregation.AggregationBuilder;
import io.redisearch.aggregation.Row;
import io.redisearch.client.Client;
import io.redisearch.client.IndexDefinition;
import redis.clients.jedis.exceptions.JedisDataException;

@SpringBootTest(classes = JSONSearchTest.Config.class)
public class JSONSearchTest {
  public static String searchIndex = "idx";

  /* A simple class that represents an object in real life */
  /* '{"title":"hello world", "tag": ["news", "article"]}' */
  private static class SomeJSON {
    @SuppressWarnings("unused")
    public String title;
    public Set<String> tag = new HashSet<String>();

    public SomeJSON() {
      this.title = "hello world";
      this.tag.add("news");
      this.tag.add("article");
    }
  }

  @Autowired
  RedisModulesOperations<String, String> modulesOperations;

  /**
   * > FT.SEARCH idx '@title:hello @tag:{news}' 
   * 1) (integer) 1 2) "doc1" 3) 1) "$"
   * 2) "{\"title\":\"hello world\",\"tag\":[\"news\",\"article\"]}"
   */
  @Test
  public void testBasicSearchOverJSON() {
    SearchOperations<String> ops = modulesOperations.opsForSearch(searchIndex);

    SearchResult result = ops.search(new Query("@title:hello @tag:{news}"));
    assertEquals(1, result.totalResults);
    Document doc = result.docs.get(0);
    assertEquals(1.0, doc.getScore(), 0);
    assertNull(doc.getPayload());
    assertEquals("{\"title\":\"hello world\",\"tag\":[\"news\",\"article\"]}", doc.get("$"));
  }

  /**
   * > FT.SEARCH idx * RETURN 3 $.tag[0] AS first_tag
   * 1) (integer) 1
   * 2) "doc1"
   * 3) 1) "first_tag"
   *    2) "news"
   */
  @Test
  public void testSearchOverJSONWithPathProjection() {
    SearchOperations<String> ops = modulesOperations.opsForSearch(searchIndex);
    SearchResult result = ops.search(new Query("*").returnFields("$.tag[0]", "AS", "first_tag"));
    assertEquals(1, result.totalResults);
    Document doc = result.docs.get(0);
    assertEquals(1.0, doc.getScore(), 0);
    assertNull(doc.getPayload());
    assertTrue(StreamSupport //
        .stream(doc.getProperties().spliterator(), false) //
        .anyMatch(p -> p.getKey().contentEquals("first_tag") && p.getValue().equals("news")));
  }

  /**
   * > FT.AGGREGATE idx * LOAD 3 $.tag[1] AS tag2
   *   1) (integer) 1
   *   2) 1) "tag2"
   *      2) "article"
   */
  @Test
  public void testAggregateLoadUsingJSONPath() {
    SearchOperations<String> ops = modulesOperations.opsForSearch(searchIndex);

    AggregationBuilder aggregation = new AggregationBuilder().load("$.tag[1]", "AS", "tag2");

    // actual search
    AggregationResult result = ops.aggregate(aggregation);
    assertEquals(1, result.totalResults);
    Row row = result.getRow(0);
    assertNotNull(row);
    assertTrue(row.containsKey("tag2"));
    assertEquals(row.getString("tag2"), "article");
  }

  @SpringBootApplication
  @Configuration
  static class Config {
    @Autowired
    RedisModulesOperations<String, String> modulesOperations;

    @Autowired
    RedisTemplate<String, String> template;

    @Bean
    CommandLineRunner loadTestData(RedisTemplate<String, String> template) {
      return args -> {
        System.out.println(">>> loadTestData...");
        JSONOperations<String> ops = modulesOperations.opsForJSON();
        // JSON.SET doc1 . '{"title":"hello world", "tag": ["news", "article"]}'
        ops.set("doc1", new SomeJSON());
      };
    }

    @Bean
    CommandLineRunner createSearchIndices(RedisModulesOperations<String, String> modulesOperations) {
      return args -> {

        System.out.println(">>> Creating " + searchIndex + " search index...");

        SearchOperations<String> ops = modulesOperations.opsForSearch(searchIndex);
        try {
          ops.dropIndex();
        } catch (JedisDataException jdee) {
          // IGNORE: Unknown Index name
        }

        // FT.CREATE idx ON JSON SCHEMA $.title AS title TEXT $.tag[*] AS tag TAG
        Schema sc = new Schema() //
            .addField(new TextField(FieldName.of("$.title").as("title"))) //
            .addField(new Field(FieldName.of("$.tag[*]").as("tag"), FieldType.Tag));

        IndexDefinition def = new IndexDefinition(IndexDefinition.Type.JSON);
        ops.createIndex(sc, Client.IndexOptions.defaultOptions().setDefinition(def));
      };
    }

    @Autowired
    RedisConnectionFactory connectionFactory;

    @PreDestroy
    void cleanUp() {
      connectionFactory.getConnection().flushAll();
    }
  }

}
