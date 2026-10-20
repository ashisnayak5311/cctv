package com.streamer.cctv;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;

import javax.imageio.ImageIO;

import java.io.Console;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CameraSender {
    private static final Pattern INPUT_TAG = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([\\w-]+)\\s*=\\s*(['\"])(.*?)\\2", Pattern.CASE_INSENSITIVE);
    private static final int CAMERA_WIDTH = 1280;
    private static final int CAMERA_HEIGHT = 720;

    private CameraSender() {
    }

    public static boolean runIfRequested(String[] args) throws Exception {
        String serverUrl = option(args, "--camera-sender=");
        if (serverUrl == null) {
            return false;
        }
        String cameraId = option(args, "--camera-id=");
        if (cameraId == null) {
            cameraId = "second-laptop";
        }
        if (!cameraId.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException(
                    "Camera IDs may contain 1-40 letters, numbers, underscores, or hyphens.");
        }

        URI baseUri = URI.create(serverUrl.endsWith("/") ? serverUrl : serverUrl + "/");
        if (!("http".equalsIgnoreCase(baseUri.getScheme()) || "https".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getPort() < 1
                || !baseUri.getPath().isEmpty() && !"/".equals(baseUri.getPath())) {
            throw new IllegalArgumentException(
                    "Provide a server base URL such as http://192.168.1.20:8080.");
        }
        run(baseUri, cameraId);
        return true;
    }

    private static void run(URI baseUri, String cameraId) throws Exception {
        String username;
        char[] password;
        Console console = System.console();
        if (console != null) {
            username = console.readLine("CCTV login username: ");
            password = console.readPassword("CCTV login password: ");
        } else {
            Scanner input = new Scanner(System.in);
            System.out.print("CCTV login username: ");
            username = input.nextLine();
            System.out.print("CCTV login password: ");
            password = input.nextLine().toCharArray();
        }

        try {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            authenticate(client, baseUri, username, password);

            URI csrfUri = baseUri.resolve("csrf");
            HttpResponse<String> csrfResponse = client.send(
                    HttpRequest.newBuilder(csrfUri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (csrfResponse.statusCode() != 200) {
                throw new IOException("Unable to obtain the CCTV security token (HTTP "
                        + csrfResponse.statusCode() + ").");
            }
            Matcher tokenMatcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(csrfResponse.body());
            if (!tokenMatcher.find()) {
                throw new IOException("The CCTV server returned no security token.");
            }
            String csrfToken = tokenMatcher.group(1);

            URI frameUri = baseUri.resolve("cameras/" + cameraId + "/frame");
            try (OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(0);
                 Java2DFrameConverter converter = new Java2DFrameConverter()) {
                grabber.setImageWidth(CAMERA_WIDTH);
                grabber.setImageHeight(CAMERA_HEIGHT);
                grabber.setFrameRate(10);
                grabber.start();
                System.out.println("Sending camera '" + cameraId + "' to " + frameUri
                        + ". Press Ctrl+C to stop sharing.");

                while (!Thread.currentThread().isInterrupted()) {
                    Frame frame = grabber.grab();
                    if (frame == null) {
                        throw new IOException("The camera stopped providing frames.");
                    }
                    if (frame.image == null) {
                        continue;
                    }
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    if (!ImageIO.write(converter.convert(frame), "jpeg", jpeg)) {
                        throw new IOException("No JPEG encoder is available.");
                    }
                    HttpRequest request = HttpRequest.newBuilder(frameUri)
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "image/jpeg")
                            .header("X-XSRF-TOKEN", csrfToken)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg.toByteArray()))
                            .build();
                    HttpResponse<Void> response = client.send(
                            request, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() != 200) {
                        throw new IOException("The CCTV server rejected a camera frame (HTTP "
                                + response.statusCode() + "). Check the login, camera name, and server URL.");
                    }
                    Thread.sleep(100);
                }
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void authenticate(HttpClient client, URI baseUri, String username, char[] password)
            throws IOException, InterruptedException {
        HttpResponse<String> loginPage = client.send(
                HttpRequest.newBuilder(baseUri.resolve("login")).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (loginPage.statusCode() != 200) {
            throw new IOException("Unable to open the CCTV login page (HTTP "
                    + loginPage.statusCode() + ").");
        }
        String csrfToken = loginPageCsrfToken(loginPage.body());
        String form = "username=" + encode(username)
                + "&password=" + encode(new String(password))
                + "&_csrf=" + encode(csrfToken);
        HttpResponse<String> loginResponse = client.send(
                HttpRequest.newBuilder(baseUri.resolve("login"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (loginResponse.statusCode() >= 400 || loginResponse.uri().getPath().endsWith("/login")) {
            throw new IOException("CCTV login failed. Check the username and password.");
        }
    }

    private static String loginPageCsrfToken(String page) throws IOException {
        Matcher tags = INPUT_TAG.matcher(page);
        while (tags.find()) {
            Matcher attributes = ATTRIBUTE.matcher(tags.group());
            String name = null;
            String value = null;
            while (attributes.find()) {
                if ("name".equalsIgnoreCase(attributes.group(1))) {
                    name = attributes.group(3);
                } else if ("value".equalsIgnoreCase(attributes.group(1))) {
                    value = attributes.group(3);
                }
            }
            if ("_csrf".equals(name) && value != null) {
                return value;
            }
        }
        throw new IOException("The CCTV login page did not contain a security token.");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String option(String[] args, String prefix) {
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length()).trim())
                .findFirst()
                .orElse(null);
    }
}
