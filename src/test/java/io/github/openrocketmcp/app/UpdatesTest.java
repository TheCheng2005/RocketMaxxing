package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/** "A newer RocketMaxxing is out": which release, which installer, how often GitHub is asked. */
class UpdatesTest {
	static final String RELEASES = """
			[
			 {"tag_name": "rocketmaxxing-v1.1.0-beta.1", "prerelease": true, "draft": false, "html_url": "https://x/beta"},
			 {"tag_name": "rocketmaxxing-v1.0.0", "prerelease": false, "draft": false, "html_url": "https://x/1.0.0",
			  "published_at": "2027-01-10T00:00:00Z",
			  "assets": [{"name": "RocketMaxxing-1.0.0-macos-intel.dmg", "browser_download_url": "https://x/intel.dmg"},
			             {"name": "RocketMaxxing-1.0.0-macos-apple-silicon.dmg", "browser_download_url": "https://x/arm.dmg"},
			             {"name": "RocketMaxxing-1.0.0-windows-x64.msi", "browser_download_url": "https://x/win.msi"}]},
			 {"tag_name": "rocketmaxxing-v2.0.0", "prerelease": false, "draft": true, "html_url": "https://x/draft"},
			 {"tag_name": "something-else-v9", "prerelease": false, "draft": false},
			 {"tag_name": "rocketmaxxing-v0.2.0", "prerelease": true, "draft": false, "html_url": "https://x/0.2.0"}
			]""";

	@Test
	void versionsInOrder() {
		assertTrue(Updates.compare("0.10.0", "0.9.9") > 0, "numbers, not text");
		assertTrue(Updates.compare("1.0.0", "1.0.0-beta.2") > 0, "a release above its betas");
		assertTrue(Updates.compare("1.0.0-beta.2", "1.0.0-beta.1") > 0);
		assertEquals(0, Updates.compare("1.0", "1.0.0"));
		assertTrue(Updates.isPre("0.1.0") && Updates.isPre("1.2.0-rc.1") && !Updates.isPre("1.2.0"));
	}

	@Test
	void offersWhatThisVersionShouldGet() {
		JsonArray rs = JsonParser.parseString(RELEASES).getAsJsonArray();
		// On a 0.x test version, betas count: the newest is 1.1.0-beta.1. The draft and other tags never do.
		Map<String, Object> beta = Updates.newest(rs, "0.1.0", "-windows-x64.msi");
		assertEquals("1.1.0-beta.1", beta.get("latest"));
		assertEquals(true, beta.get("newer"));
		// On a stable version, only stable releases.
		Map<String, Object> stable = Updates.newest(rs, "1.0.0", "-windows-x64.msi");
		assertEquals("1.0.0", stable.get("latest"));
		assertEquals(false, stable.get("newer"), "already on the newest stable");
		Map<String, Object> older = Updates.newest(JsonParser.parseString(RELEASES.replace("1.1.0-beta.1", "0.0.1")).getAsJsonArray(),
				"0.1.0", "-macos-apple-silicon.dmg");
		assertEquals("1.0.0", older.get("latest"));
		assertEquals("https://x/arm.dmg", older.get("download"), "the installer for this computer");
		assertEquals("https://x/1.0.0", older.get("url"));
		assertEquals("-macos-apple-silicon.dmg", Updates.platform("Mac OS X", "aarch64"));
		assertEquals("-macos-intel.dmg", Updates.platform("Mac OS X", "x86_64"));
		assertEquals("-windows-x64.msi", Updates.platform("Windows 11", "amd64"));
		assertEquals("-linux-x64.deb", Updates.platform("Linux", "amd64"));
	}

	@Test
	void asksGitHubOnceADayAndQuietlyOffline(@TempDir Path dir) throws Exception {
		HttpServer gh = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger hits = new AtomicInteger();
		gh.createContext("/", ex -> {
			hits.incrementAndGet();
			byte[] b = RELEASES.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, b.length);
			ex.getResponseBody().write(b);
			ex.close();
		});
		gh.start();
		String before = System.getProperty("rocketmaxxing.updates.url");
		try {
			System.setProperty("rocketmaxxing.updates.url", "http://127.0.0.1:" + gh.getAddress().getPort() + "/releases");
			Path cache = dir.resolve("update-check.json");
			Map<String, Object> first = Updates.check("0.1.0", cache, false);
			assertEquals("1.1.0-beta.1", first.get("latest"), first.toString());
			assertEquals(true, first.get("newer"));
			Map<String, Object> again = Updates.check("0.1.0", cache, false);
			assertEquals(1, hits.get(), "the second check today uses the first answer");
			assertEquals(true, again.get("fromCache"));
			Updates.check("0.1.0", cache, true);
			assertEquals(2, hits.get(), "Check now asks again");
			gh.stop(0);
			Map<String, Object> offline = Updates.check("0.1.0", dir.resolve("other.json"), true);
			assertTrue(offline.get("error").toString().contains("Could not reach"), offline.toString());
			assertFalse(offline.containsKey("newer"));
		} finally {
			gh.stop(0);
			if (before == null) {
				System.clearProperty("rocketmaxxing.updates.url");
			} else {
				System.setProperty("rocketmaxxing.updates.url", before);
			}
		}
	}
}
