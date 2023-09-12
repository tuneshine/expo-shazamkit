import { NativeModulesProxy } from "expo-modules-core";

export default NativeModulesProxy.ExpoShazamKit || {
  isAvailable(): boolean {
    return false;
  },

  startListening() {
    return new Promise(resolve => resolve(false))
  },

  stopListening() {
    return new Promise(resolve => resolve(false))
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
