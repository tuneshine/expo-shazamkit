import {
  ConfigPlugin,
  createRunOncePlugin,
  withInfoPlist,
  withStringsXml,
} from "@expo/config-plugins";

const pkg = require("../../package.json");

const MICROPHONE_USAGE = "Allow $(PRODUCT_NAME) to access your microphone";

type ShazamKitPluginProps = {
  microphonePermission?: string;
  developerToken?: string;
};

const withShazamKit: ConfigPlugin<ShazamKitPluginProps> = (
  config,
  { microphonePermission, developerToken } = {}
) => {
  // iOS microphone permission
  config = withInfoPlist(config, (config) => {
    config.modResults.NSMicrophoneUsageDescription =
      microphonePermission ||
      config.modResults.NSMicrophoneUsageDescription ||
      MICROPHONE_USAGE;
    return config;
  });

  // Write the developerToken to Android strings.xml
  if (developerToken) {
    config = withStringsXml(config, (config) => {
      if (!config.modResults.resources) {
        config.modResults.resources = {};
      }
      if (!config.modResults.resources.string) {
        config.modResults.resources.string = [];
      }

      // Remove any existing 'shazam_developer_token' entries
      config.modResults.resources.string = config.modResults.resources.string.filter(
        (item) => item.$.name !== "shazam_developer_token"
      );

      config.modResults.resources.string.push({
        $: { name: "shazam_developer_token" },
        _: developerToken,
      });
      return config;
    });
  }

  return config;
};

export default createRunOncePlugin(withShazamKit, pkg.name, pkg.version);
