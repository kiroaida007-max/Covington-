package com.example.app;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.util.List;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public class Main {
    public static class User { public String id; public String name; }

    public interface Api {
        @GET("users/{id}") Call<User> user(@Path("id") String id);
        @GET("users") Call<List<User>> users(@Query("page") int page);
        @POST("users") Call<User> create(@Body User u);
    }

    public static void main(String[] args) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(new okhttp3.Interceptor() {
                @Override public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain) throws java.io.IOException {
                    return chain.proceed(chain.request().newBuilder().header("X-App", "demo").build());
                }
            })
            .build();
        Moshi moshi = new Moshi.Builder().build();
        Retrofit retrofit = new Retrofit.Builder().baseUrl("https://api.example.com/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        Api api = retrofit.create(Api.class);
        Response<User> response = api.user("1").execute();
        System.out.println(response.body() == null ? "none" : response.body().name);
        JsonAdapter<User> adapter = moshi.adapter(User.class);
        System.out.println(adapter.toJson(new User()));
        okio.Buffer buffer = new okio.Buffer();
        buffer.writeUtf8("hello");
        System.out.println(buffer.readUtf8());
    }
}
