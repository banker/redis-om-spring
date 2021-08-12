package com.redislabs.spring.annotations.document.fixtures;

import java.util.Set;

import org.springframework.data.repository.query.Param;

import com.redislabs.spring.annotations.Aggregation;
import com.redislabs.spring.annotations.Query;
import com.redislabs.spring.repository.RedisDocumentRepository;

import io.redisearch.AggregationResult;
import io.redisearch.SearchResult;

public interface MyDocRepository extends RedisDocumentRepository<MyDoc, String>, MyDocQueries {
  /**
   * > FT.SEARCH idx '@title:hello @tag:{news}' 
   * 1) (integer) 1 2) "doc1" 3) 1) "$"
   * 2) "{\"title\":\"hello world\",\"tag\":[\"news\",\"article\"]}"
   */
  @Query("@title:title @tag:{:tags}")
  Iterable<MyDoc> findByTitleAndTags(@Param("title") String title, @Param("tags") Set<String> tags);
  
  /**
   * > FT.SEARCH idx * RETURN 3 $.tag[0] AS first_tag
   * 1) (integer) 1
   * 2) "doc1"
   * 3) 1) "first_tag"
   *    2) "news"
   */
  @Query("$.tag[0] AS first_tag")
  SearchResult getFirstTag();
  
  /**
   * > FT.AGGREGATE idx * LOAD 3 $.tag[1] AS tag2
   *   1) (integer) 1
   *   2) 1) "tag2"
   *      2) "article"
   */
  @Aggregation("$.tag[1] AS tag2")
  AggregationResult getFirstTagWithAggregation();
}
