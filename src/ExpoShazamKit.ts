import { requireOptionalNativeModule } from "expo-modules-core";

const ExpoShazamKitModule = requireOptionalNativeModule("ExpoShazamKit");

console.log("DEBUG: ExpoShazamKit module loaded?", !!ExpoShazamKitModule);
console.log("DEBUG: ExpoShazamKit module:", ExpoShazamKitModule);

export default ExpoShazamKitModule || {
  isAvailable(): boolean {
    console.log("STUB: isAvailable fallback called");
    return false;
  },

  startListening() {
    console.log("STUB: startListening fallback called - native module not loaded!");
  },

  stopListening() {
    console.log("STUB: stopListening fallback called");
  },

  setDeveloperToken(token: string) {
    console.log("STUB: setDeveloperToken fallback called");
  },

  addToShazamLibrary() {
    return { success: false };
  },

  addListener() {
    // Nothing to do; unsupported platform.
    return Promise.resolve();
  },

  removeListeners() {
    // Nothing to do; unsupported platform.
    return Promise.resolve();
  },
};
