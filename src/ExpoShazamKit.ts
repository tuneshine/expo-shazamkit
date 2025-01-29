import { NativeModulesProxy } from "expo-modules-core";

export default NativeModulesProxy.ExpoShazamKit || {
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

