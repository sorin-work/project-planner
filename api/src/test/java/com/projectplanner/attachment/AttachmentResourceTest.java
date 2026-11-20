package com.projectplanner.attachment;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.LockLog;
import com.projectplanner.support.TestClock;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class AttachmentResourceTest {

    private static final byte[] PNG = png(200);
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Inject
    AttachmentStore store;

    @Inject
    AttachmentService service;

    private static byte[] png(int size) {
        byte[] bytes = new byte[size];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(head, 0, bytes, 0, head.length);
        Arrays.fill(bytes, head.length, size, (byte) 7);
        return bytes;
    }

    private record Setup(Account owner, long clientId, long projectId, long taskId) {
    }

    private static Setup setup(String name, String code) {
        Account owner = Api.signup(name);
        long clientId = Api.createClient(owner, "Client of " + code);
        long projectId = Api.createProject(owner, code, clientId);
        long taskId = Api.createTask(owner, projectId, Map.of("title", "Design"));
        return new Setup(owner, clientId, projectId, taskId);
    }

    private static Response upload(Account who, long taskId, String fileName, byte[] bytes) {
        return given().auth().oauth2(who.token()).multiPart("file", fileName, bytes, "application/octet-stream")
                .post("/api/tasks/" + taskId + "/attachments");
    }

    private static long uploaded(Account who, long taskId, String fileName, byte[] bytes) {
        Number id = upload(who, taskId, fileName, bytes).then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    private static String link(Account who, long attachmentId, boolean preview) {
        return who.as().post("/api/attachments/" + attachmentId + "/link?preview=" + preview).then().statusCode(200)
                .extract().path("url");
    }

    private String keyOf(long attachmentId) {
        return QuarkusTransaction.requiringNew().call(() -> Attachment.<Attachment>findById(attachmentId).storageKey);
    }

    @Test
    void aFileGoesUpAndComesBackThroughAShortLivedLinkWithHeadersThatKeepItFromRunning() {
        Setup s = setup("Ann", "FILES");
        long id = uploaded(s.owner(), s.taskId(), "Mock-up.png", PNG);

        s.owner().as().get("/api/tasks/" + s.taskId() + "/attachments").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].fileName", equalTo("Mock-up.png"))
                .body("[0].contentType", equalTo("image/png"))
                .body("[0].sizeBytes", equalTo(PNG.length))
                .body("[0].uploaderName", equalTo("Ann"))
                .body("[0].previewable", equalTo(true))
                .body("[0].canDelete", equalTo(true));

        // The link needs no token, and the file is a download unless a preview was asked for.
        String url = link(s.owner(), id, false);
        Response download = given().get(url);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.asByteArray()).isEqualTo(PNG);
        assertThat(download.contentType()).isEqualTo("image/png");
        assertThat(download.header("Content-Disposition")).isEqualTo("attachment; filename=\"Mock-up.png\"; filename*=UTF-8''Mock-up.png");
        assertThat(download.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download.header("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");
        assertThat(download.header("Cache-Control")).isEqualTo("private, no-store");

        // An image may be shown in the page; a PDF is a download even when a preview is asked for.
        assertThat(given().get(link(s.owner(), id, true)).header("Content-Disposition")).startsWith("inline; ");
        long pdf = uploaded(s.owner(), s.taskId(), "Signed off.pdf", PDF);
        Response pdfDownload = given().get(link(s.owner(), pdf, true));
        assertThat(pdfDownload.header("Content-Disposition")).startsWith("attachment; filename=\"Signed off.pdf\"");
        assertThat(pdfDownload.contentType()).isEqualTo("application/pdf");

        // A name that is not plain ASCII, or that tries to name a folder, is sent safely.
        long odd = uploaded(s.owner(), s.taskId(), "../../Brief final.txt", "Plain words.".getBytes(StandardCharsets.UTF_8));
        s.owner().as().get("/api/tasks/" + s.taskId() + "/attachments").then()
                .body("find { it.id == " + odd + " }.fileName", equalTo("Brief final.txt"));
        assertThat(given().get(link(s.owner(), odd, false)).header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"Brief final.txt\"; filename*=UTF-8''Brief%20final.txt");
    }

    @Test
    void aLinkWorksForTwoMinutesAndOnlyAsItWasSigned() {
        Setup s = setup("Ben", "LINKS");
        long id = uploaded(s.owner(), s.taskId(), "a.png", PNG);
        long other = uploaded(s.owner(), s.taskId(), "b.png", PNG);
        Instant now = Instant.parse("2026-03-02T10:00:00Z");
        TestClock.fix(now);
        try {
            String url = link(s.owner(), id, false);
            given().get(url).then().statusCode(200);
            // Another attachment, another user, a preview that was not signed, or a changed signature: refused.
            given().get(url.replace("/attachments/" + id + "/", "/attachments/" + other + "/")).then().statusCode(403)
                    .body("code", equalTo("link_invalid"));
            given().get(url.replace("user=", "user=9")).then().statusCode(403);
            given().get(url.replace("inline=false", "inline=true")).then().statusCode(403);
            given().get(url.substring(0, url.length() - 2) + "00").then().statusCode(403);
            given().get(url.replaceAll("signature=.*", "signature=nothex")).then().statusCode(403);
            // Two minutes later it has expired.
            TestClock.fix(now.plus(SignedLinks.TTL).plusSeconds(1));
            given().get(url).then().statusCode(403).body("code", equalTo("link_invalid"));
        } finally {
            TestClock.reset();
        }
    }

    @Test
    void onlyFilesOnTheListAreTakenAndOnlyWhatTheirNameSays() {
        Setup s = setup("Cy", "TYPES");
        upload(s.owner(), s.taskId(), "logo.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8))
                .then().statusCode(415).body("code", equalTo("unsupported_file_type"));
        upload(s.owner(), s.taskId(), "page.html", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8))
                .then().statusCode(415);
        upload(s.owner(), s.taskId(), "photo.png", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8))
                .then().statusCode(415);
        upload(s.owner(), s.taskId(), "notes.txt", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
                .then().statusCode(415);
        upload(s.owner(), s.taskId(), "brief.pdf", PNG).then().statusCode(415);
        given().auth().oauth2(s.owner().token()).multiPart("other", "x.png", PNG).post("/api/tasks/" + s.taskId() + "/attachments")
                .then().statusCode(400).body("code", equalTo("file_required"));
        s.owner().as().get("/api/tasks/" + s.taskId() + "/attachments").then().body("$", hasSize(0));
    }

    @Test
    void aFileAndAnOrganisationHaveTheirLimits() {
        // In the tests a file may have 1 MB and an organisation 3 MB.
        Setup s = setup("Dee", "LIMITS");
        upload(s.owner(), s.taskId(), "big.png", png(1048577)).then().statusCode(413).body("code", equalTo("file_too_large"));
        uploaded(s.owner(), s.taskId(), "one.png", png(1048576));
        uploaded(s.owner(), s.taskId(), "two.png", png(1048576));
        uploaded(s.owner(), s.taskId(), "three.png", png(1048576));
        upload(s.owner(), s.taskId(), "four.png", png(10)).then().statusCode(413).body("code", equalTo("storage_full"));

        // Another organisation has its own space.
        Setup other = setup("Eli", "LIMITS2");
        uploaded(other.owner(), other.taskId(), "one.png", png(1048576));
    }

    @Test
    void theClientOfTheProjectSeesAndAttachesAndRemovesOnlyItsOwnFiles() {
        Setup s = setup("Fay", "WHO");
        long ownerFile = uploaded(s.owner(), s.taskId(), "brief.pdf", PDF);

        Account viewer = Api.signup("Gus");
        Api.addMember(s.owner(), viewer, "CLIENT", s.clientId());
        long viewerFile = uploaded(viewer, s.taskId(), "from the client.pdf", PDF);
        viewer.as().get("/api/tasks/" + s.taskId() + "/attachments").then().statusCode(200)
                .body("find { it.id == " + ownerFile + " }.canDelete", equalTo(false))
                .body("find { it.id == " + viewerFile + " }.canDelete", equalTo(true));
        given().get(link(viewer, ownerFile, false)).then().statusCode(200);
        viewer.as().delete("/api/attachments/" + ownerFile).then().statusCode(403);

        Account member = Api.signup("Hal");
        Api.addMember(s.owner(), member, "MEMBER");
        member.as().delete("/api/attachments/" + viewerFile).then().statusCode(403);
        Account manager = Api.signup("Ida");
        Api.addMember(s.owner(), manager, "MANAGER");
        manager.as().delete("/api/attachments/" + viewerFile).then().statusCode(204);

        // A client viewer of another client, and a stranger, learn nothing.
        Account otherViewer = Api.signup("Jo");
        Api.addMember(s.owner(), otherViewer, "CLIENT", Api.createClient(s.owner(), "Another client"));
        Account stranger = Api.signup("Kai");
        for (Account outsider : List.of(otherViewer, stranger)) {
            outsider.as().get("/api/tasks/" + s.taskId() + "/attachments").then().statusCode(404);
            outsider.as().post("/api/attachments/" + ownerFile + "/link").then().statusCode(404);
            outsider.as().delete("/api/attachments/" + ownerFile).then().statusCode(404);
            upload(outsider, s.taskId(), "x.pdf", PDF).then().statusCode(404);
        }
        given().get("/api/tasks/" + s.taskId() + "/attachments").then().statusCode(401);
    }

    @Test
    void theFilesGoWithTheirTaskTheirProjectAndTheirRow() {
        Setup s = setup("Lou", "CLEAN");
        long subtask = Api.createTask(s.owner(), s.projectId(), Map.of("title", "Sketches", "parentId", s.taskId()));
        String own = keyOf(uploaded(s.owner(), s.taskId(), "a.png", PNG));
        String onSubtask = keyOf(uploaded(s.owner(), subtask, "b.png", PNG));
        long removed = uploaded(s.owner(), s.taskId(), "c.png", PNG);
        String removedKey = keyOf(removed);
        long other = Api.createTask(s.owner(), s.projectId(), Map.of("title", "Copy"));
        String onOther = keyOf(uploaded(s.owner(), other, "d.png", PNG));
        assertThat(List.of(own, onSubtask, removedKey, onOther)).allMatch(store::exists);

        s.owner().as().delete("/api/attachments/" + removed).then().statusCode(204);
        assertThat(store.exists(removedKey)).isFalse();

        s.owner().as().delete("/api/tasks/" + s.taskId()).then().statusCode(204);
        assertThat(store.exists(own)).isFalse();
        assertThat(store.exists(onSubtask)).isFalse();
        assertThat(store.exists(onOther)).isTrue();

        s.owner().as().delete("/api/projects/" + s.projectId()).then().statusCode(204);
        assertThat(store.exists(onOther)).isFalse();
    }

    @Test
    void theSweepRemovesOldFilesWithoutARowAndNothingElse() throws IOException {
        Setup s = setup("Max", "SWEEP");
        String kept = keyOf(uploaded(s.owner(), s.taskId(), "a.png", PNG));
        Path old = orphan("0123456789abcdef0123456789abcdef", Duration.ofHours(2));
        Path fresh = orphan("fedcba9876543210fedcba9876543210", Duration.ofMinutes(5));
        Path foreign = store.root().resolve("not-ours.txt");
        Files.writeString(foreign, "left alone");
        Files.setLastModifiedTime(foreign, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(store.path(kept), FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        service.sweep();
        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
        assertThat(foreign).exists();
        assertThat(store.exists(kept)).isTrue();
        Files.deleteIfExists(fresh);
        Files.deleteIfExists(foreign);
    }

    private Path orphan(String key, Duration age) throws IOException {
        Path file = store.path(key);
        Files.createDirectories(file.getParent());
        Files.write(file, PNG);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
        return file;
    }

    @Test
    void aCopyOfTheProjectTakesTheFilesOnlyWhenAskedTo() {
        Setup s = setup("Ned", "COPYF");
        String original = keyOf(uploaded(s.owner(), s.taskId(), "brief.pdf", PDF));
        s.owner().as().get("/api/projects/" + s.projectId() + "/duplicate").then()
                .body("attachments", equalTo(1)).body("attachmentBytes", equalTo(PDF.length));

        Number without = s.owner().as().body(Map.of("name", "Without", "code", "COPYF-2", "startDate", "2026-06-01"))
                .post("/api/projects/" + s.projectId() + "/duplicate").then().statusCode(201).extract().path("id");
        Number withFiles = s.owner().as().body(Map.of("name", "With", "code", "COPYF-3", "startDate", "2026-06-01", "copyAttachments", true))
                .post("/api/projects/" + s.projectId() + "/duplicate").then().statusCode(201).extract().path("id");

        Number taskWithout = s.owner().as().get("/api/projects/" + without + "/tasks").then().extract().path("[0].id");
        s.owner().as().get("/api/tasks/" + taskWithout + "/attachments").then().body("$", hasSize(0));
        Number taskWith = s.owner().as().get("/api/projects/" + withFiles + "/tasks").then().extract().path("[0].id");
        Number copied = s.owner().as().get("/api/tasks/" + taskWith + "/attachments").then()
                .body("$", hasSize(1)).body("[0].fileName", equalTo("brief.pdf")).extract().path("[0].id");
        String copyKey = keyOf(copied.longValue());
        assertThat(copyKey).isNotEqualTo(original);
        assertThat(given().get(link(s.owner(), copied.longValue(), false)).asByteArray()).isEqualTo(PDF);

        // Deleting the copy leaves the source's file alone.
        s.owner().as().delete("/api/projects/" + withFiles).then().statusCode(204);
        assertThat(store.exists(copyKey)).isFalse();
        assertThat(store.exists(original)).isTrue();
    }

    @Test
    void uploadsThatArriveTogetherNeverPassTheOrganisationsLimitTogether() throws Exception {
        // 3 MB in the tests: room for exactly two files of 1 MB after the first one.
        Setup s = setup("Ola", "RACEF");
        Account viewer = Api.signup("Pip");
        Api.addMember(s.owner(), viewer, "CLIENT", s.clientId());
        uploaded(s.owner(), s.taskId(), "first.png", png(1048576));

        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Account who = i % 2 == 0 ? s.owner() : viewer;
                String name = "file" + i + ".png";
                Callable<Integer> upload = () -> {
                    go.await();
                    return upload(who, s.taskId(), name, png(1048576)).statusCode();
                };
                answers.add(pool.submit(upload));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                codes.add(answer.get(30, TimeUnit.SECONDS));
            }
            assertThat(codes).containsExactlyInAnyOrder(201, 201, 413, 413, 413);
        } finally {
            pool.shutdownNow();
        }
        s.owner().as().get("/api/tasks/" + s.taskId() + "/attachments").then().body("$", hasSize(3));
    }

    @Test
    void anUploadLocksTheOrganisationAndNoProjectOrMembership() {
        Setup s = setup("Quin", "LOCKF");
        LockLog.start();
        uploaded(s.owner(), s.taskId(), "a.png", PNG);
        assertThat(LockLog.stop()).isEmpty();
    }

    @Test
    void aCopyLeavesOutFilesThatAreMissingAndSaysHowMany() throws IOException {
        Setup s = setup("Rae", "MISSF");
        uploaded(s.owner(), s.taskId(), "kept.pdf", PDF);
        String gone = keyOf(uploaded(s.owner(), s.taskId(), "gone.pdf", PDF));
        Files.delete(store.path(gone));

        Number copy = s.owner().as().body(Map.of("name", "Copy", "code", "MISSF-2", "startDate", "2026-06-01", "copyAttachments", true))
                .post("/api/projects/" + s.projectId() + "/duplicate").then().statusCode(201)
                .body("attachmentsCopied", equalTo(1)).body("attachmentsSkipped", equalTo(1)).extract().path("id");
        Number task = s.owner().as().get("/api/projects/" + copy + "/tasks").then().extract().path("[0].id");
        s.owner().as().get("/api/tasks/" + task + "/attachments").then().body("fileName", equalTo(List.of("kept.pdf")));
        // Without the files the answer says nothing about them.
        s.owner().as().body(Map.of("name", "Plain", "code", "MISSF-3", "startDate", "2026-06-01"))
                .post("/api/projects/" + s.projectId() + "/duplicate").then().statusCode(201)
                .body("containsKey('attachmentsCopied')", equalTo(false));
    }

    @Test
    void aFileThatIsALinkIsNeverServed() throws IOException {
        Setup s = setup("Sid", "LINKF");
        long id = uploaded(s.owner(), s.taskId(), "a.png", PNG);
        Path file = store.path(keyOf(id));
        Path elsewhere = Files.createTempFile("not-an-attachment", ".txt");
        Files.writeString(elsewhere, "secret");
        Files.delete(file);
        Files.createSymbolicLink(file, elsewhere);
        try {
            given().get(link(s.owner(), id, false)).then().statusCode(404);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(elsewhere);
        }
    }
}

