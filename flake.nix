{
  description = "Koru — Trustable AI racing coach dev environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;          # Android SDK components
          config.android_sdk.accept_license = true;
        };

        # ---------- Android SDK ----------
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          # Keep in sync with pixel-android-app/app/build.gradle.kts
          platformVersions = [ "35" ];
          buildToolsVersions = [ "34.0.0" "35.0.0" ];
          includeNDK = false;
          includeSources = false;
          includeSystemImages = false;
          includeEmulator = false;
          # Extras needed by the Gradle plugin
          extraLicenses = [
            "android-sdk-license"
            "android-sdk-preview-license"
          ];
        };
        androidSdk = androidComposition.androidsdk;

        # ---------- JDK ----------
        jdk = pkgs.jdk17;

        # ---------- Python (streaming-telemetry-server) ----------
        python = pkgs.python312;

      in {
        devShells.default = pkgs.mkShell {
          name = "koru-dev";

          packages = [
            # Java / Android
            jdk
            androidSdk

            # Kotlin / Gradle (uses the wrapper, but gradle helps with tooling)
            pkgs.gradle

            # Node.js (scripts/ and sonoma-training-e2e/)
            pkgs.nodejs_22

            # Python (streaming-telemetry-server/)
            python
            pkgs.uv

            # General dev tools
            pkgs.git
            pkgs.ripgrep
            pkgs.jq
          ];

          # Point Gradle & Android tools at the Nix-provided JDK and SDK
          shellHook = ''
            export JAVA_HOME="${jdk.home}"
            export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="$ANDROID_HOME/tools:$ANDROID_HOME/tools/bin:$ANDROID_HOME/platform-tools:$PATH"

            # Gradle should use the Nix JDK even if gradle.properties says otherwise
            export GRADLE_OPTS="-Dorg.gradle.java.home=$JAVA_HOME"

            echo "🏁 Koru dev shell ready"
            echo "   JDK:         $(java -version 2>&1 | head -1)"
            echo "   Android SDK: $ANDROID_HOME"
            echo "   Node:        $(node --version)"
            echo "   Python:      $(python3 --version)"
            echo "   uv:          $(uv --version)"
          '';
        };
      }
    );
}
