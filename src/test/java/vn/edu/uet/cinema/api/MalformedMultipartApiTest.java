package vn.edu.uet.cinema.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import vn.edu.uet.cinema.TestcontainersConfiguration;

/**
 * A multipart Content-Type without a boundary must not reach the client as a 500. Runs against the real servlet
 * container because MockMvc's request never parses multipart bodies, so it cannot reproduce the failure.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MalformedMultipartApiTest {

	private static final String MULTIPART_WITHOUT_BOUNDARY = "multipart/form-data";

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@Value("${local.server.port}")
	int port;

	@AfterEach
	void closeClient() {
		http.close();
	}

	@Test
	void malformedMultipartContentTypeIsIgnoredByEndpointsThatReadNoBody() throws Exception {
		String admin = token("admin@cinema.local", "admin12345");

		HttpResponse<String> deleted = send(HttpRequest.newBuilder(uri("/api/auditoriums/" + UUID.randomUUID()))
			.header("Authorization", "Bearer " + admin).header("Content-Type", MULTIPART_WITHOUT_BOUNDARY)
			.DELETE());
		assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(404);
		assertProblemJson(deleted);

		HttpResponse<String> movies = send(HttpRequest.newBuilder(uri("/api/movies"))
			.header("Content-Type", MULTIPART_WITHOUT_BOUNDARY).GET());
		assertThat(movies.statusCode()).as(movies.body()).isEqualTo(200);
	}

	@Test
	void malformedMultipartBodyOnJsonEndpointIsUnsupportedMediaType() throws Exception {
		HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/auth/login"))
			.header("Content-Type", MULTIPART_WITHOUT_BOUNDARY).POST(BodyPublishers.ofString("not multipart")));
		assertThat(login.statusCode()).as(login.body()).isEqualTo(415);
		assertProblemJson(login);
	}

	private static void assertProblemJson(HttpResponse<String> response) {
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
			assertThat(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(MediaType.parseMediaType(type))).isTrue());
	}

	private String token(String email, String password) throws Exception {
		HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/auth/login"))
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")));
		assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
		return JsonPath.read(login.body(), "$.accessToken");
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return http.send(request.build(), BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
