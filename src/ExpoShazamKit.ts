import { NativeModulesProxy } from "expo-modules-core";

export default NativeModulesProxy.ExpoShazamKit || {
  isAvailable(): boolean {
    return false;
  },

  async startListening() {
    return false;
  },

  stopListening() {
    return false;
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
