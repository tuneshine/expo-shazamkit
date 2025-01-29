import {
  ConfigPlugin,
  createRunOncePlugin,
  withInfoPlist,
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

  return config;
};

export default createRunOncePlugin(withShazamKit, pkg.name, pkg.version);
