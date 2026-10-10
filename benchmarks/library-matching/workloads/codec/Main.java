package com.example.app;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okio.Buffer;
import okio.ByteString;
import okio.GzipSink;
import okio.GzipSource;
import okio.Okio;

/** A separate workload: JSON collections, compression and request construction without Retrofit. */
public class Main {
    public static void main(String[] args) throws Exception {
        String input = args.length == 0 ? "[\"alpha\",\"beta\"]" : args[0];
        Moshi moshi = new Moshi.Builder().build();
        JsonAdapter<List<String>> adapter = moshi.adapter(Types.newParameterizedType(List.class, String.class));
        List<String> values = adapter.fromJson(input);
        Map<String, String> map = new LinkedHashMap<>();
        for (String value : values) map.put(value, ByteString.encodeUtf8(value).sha256().hex());
        JsonAdapter<Map<String, String>> objectAdapter = moshi.adapter(Types.newParameterizedType(Map.class, String.class, String.class));
        String json = objectAdapter.toJson(map);
        Buffer compressed = new Buffer();
        try (okio.BufferedSink sink = Okio.buffer(new GzipSink(compressed))) { sink.writeUtf8(json); }
        try (okio.BufferedSource source = Okio.buffer(new GzipSource(compressed))) { System.out.println(source.readUtf8()); }
        HttpUrl url = HttpUrl.get("https://example.com/").newBuilder().addPathSegment("submit").addQueryParameter("payload", json).build();
        Request request = new Request.Builder().url(url).headers(Headers.of("X-Codec", "gzip"))
            .post(new FormBody.Builder().add("payload", json).build()).build();
        System.out.println(request.url().encodedQuery());
        System.out.println(request.body().contentLength());
    }
}
