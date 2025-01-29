import { NativeModulesProxy } from "expo-modules-core";

const ShazamKitNative = NativeModulesProxy.ExpoShazamKit || {
  isAvailable(): boolean {
    return true;
  },
  startListening() {},
  stopListening() {},
  addToShazamLibrary() {
    return { success: false };
  },
  setDeveloperToken(_token: string) {
    // no-op on web
  },
  addListener() {},
  removeListeners() {},
};

// Export your JS module as usual:
export default {
  ...ShazamKitNative,

  // Provide a convenient JS method to set the token
  setDeveloperToken(token: string) {
    return ShazamKitNative.setDeveloperToken?.(token);
  },
};
