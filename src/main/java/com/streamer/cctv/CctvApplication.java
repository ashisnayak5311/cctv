package com.streamer.cctv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;

@SpringBootApplication
public class CctvApplication {

	public static void main(String[] args) {
		try {
			if (CameraSender.runIfRequested(args)) {
				return;
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to start the CCTV camera sender.", exception);
		}
		String mode = requestedMode(args);
		if (mode == null) {
			mode = promptForMode();
		}
		if (!mode.equals("local") && !mode.equals("global")) {
			throw new IllegalArgumentException("Streaming mode must be 'local' or 'global'.");
		}
		System.out.println("Starting CCTV in " + mode.toUpperCase() + " mode.");

		SpringApplication application = new SpringApplication(CctvApplication.class);
		// Global-only components are activated by this profile; default Local mode remains unchanged.
		if (mode.equals("global")) {
			validateGlobalCredentials();
			application.setAdditionalProfiles("global");
		}
		application.run(args);
	}

	private static String requestedMode(String[] args) {
		return Arrays.stream(args)
				.filter(argument -> argument.startsWith("--streaming.mode="))
				.map(argument -> argument.substring("--streaming.mode=".length()).trim().toLowerCase())
				.findFirst()
				.orElseGet(() -> {
					String environmentMode = System.getenv("CCTV_STREAMING_MODE");
					return environmentMode == null || environmentMode.isBlank()
							? null
							: environmentMode.trim().toLowerCase();
				});
	}

	private static String promptForMode() {
		System.out.println("Select streaming mode:");
		System.out.println("1) Local - stream on your local network only");
		System.out.println("2) Global - publish through a Cloudflare Quick Tunnel");
		System.out.print("Enter 1 or 2 [1]: ");
		try {
			BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
			String selection = input.readLine();
			if (selection == null || selection.isBlank() || selection.trim().equals("1")) {
				return "local";
			}
			if (selection.trim().equals("2")) {
				return "global";
			}
			throw new IllegalArgumentException("Enter 1 for Local or 2 for Global.");
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to read streaming mode selection.", exception);
		}
	}

	private static void validateGlobalCredentials() {
		String username = System.getenv("CCTV_GLOBAL_USERNAME");
		String password = System.getenv("CCTV_GLOBAL_PASSWORD");
		if (username == null || username.isBlank()
				|| password == null || password.length() < 12
				|| username.equals("cctv") || password.equals("cctv")) {
			throw new IllegalStateException(
					"Global mode requires CCTV_GLOBAL_USERNAME and a private CCTV_GLOBAL_PASSWORD "
							+ "of at least 12 characters. Do not use the local cctv/cctv credentials.");
		}
	}

}
