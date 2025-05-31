import ExpoShazamKit from "./ExpoShazamKit";
import { MatchedItem } from "./ExpoShazamKit.types";

export function isAvailable(): boolean {
  return ExpoShazamKit.isAvailable();
}

export async function startListening(): Promise<MatchedItem[]> {
  return await ExpoShazamKit.startListening();
}

export function stopListening() {
  ExpoShazamKit.stopListening();
}

export function setDeveloperToken(token: string): void {
  return ExpoShazamKit.setDeveloperToken(token);
}

export async function addToShazamLibrary(): Promise<{ success: boolean }> {
  return await ExpoShazamKit.addToShazamLibrary();
}

export { MatchedItem };
