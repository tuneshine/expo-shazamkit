import { NativeModulesProxy } from "expo-modules-core";

export default NativeModulesProxy.ExpoShazamKit || {
  isAvailable(): boolean {
    console.log("STUB: isAvailable fallback called");
    return true;
  },

  startListening() {
    console.log("STUB: startListening fallback called - native module not loaded!");
  },

  stopListening() {
    console.log("STUB: stopListening fallback called");
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
