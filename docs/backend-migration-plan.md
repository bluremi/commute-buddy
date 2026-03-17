# Backend Migration Plan: Commute Buddy

## Objective
Migrate the Gemini API calls from the Android client to a secure backend server. This migration closes the remaining client-side security gaps (token harvesting and UI automation), establishes a server-side user data layer using anonymized IDs, enforces reliable rate limits, and creates a foundation for usage analytics.

## Phase 1: Authentication and Data Layer (Firestore)
Establish an anonymous identity system and a NoSQL database to track users, their devices, and API usage.

* **Firebase Anonymous Authentication:** Implement Firebase Auth in the Android app. Upon first launch, request an anonymous UID. This UID persists across sessions and serves as the anonymized user ID.
* **Firestore Provisioning:** Set up Cloud Firestore with the following schema:
    * `users/{uid}`: Document containing `accountCreatedAt` (timestamp) and `connectedWearables` (array of strings, e.g., ["Garmin", "WearOS"]).
    * `users/{uid}/usage/{YYYY-MM-DD}`: Subcollection document containing `requestCount` (integer) and `lastRequestTimestamp` (timestamp).

## Phase 2: Backend Logic (Cloud Functions)
Move the AI decision engine and API key to a secure server environment.

* **Deploy Cloud Function:** Create an HTTP-triggered Firebase Cloud Function (e.g., `getCommuteDecision`).
* **Secure API Key:** Store the Gemini API key in Google Cloud Secret Manager and grant access only to the Cloud Function.
* **Endpoint Security:** Configure the Cloud Function to require both a valid Firebase Auth UID and a valid Firebase App Check (Play Integrity) token before executing.
* **Server-Side Rate Limiting:** * On invocation, the function reads the user's `usage/{YYYY-MM-DD}` document.
    * If `requestCount` >= 50, return an HTTP 429 (Too Many Requests).
    * If under the limit, increment `requestCount`, execute the Gemini API call via the server SDK, and return the `CommuteStatus` payload.

## Phase 3: Android Client Refactor
Update the client app to communicate with the new backend and remove local AI dependencies.

* **Remove Client AI SDK:** Delete the Firebase AI Logic SDK (`com.google.firebase:firebase-ai`) from `android/app/build.gradle.kts`.
* **Device Registration Sync:** Update the app's startup sequence (or watch pairing logic) to write the current watch types to the `connectedWearables` array in the `users/{uid}` Firestore document.
* **Refactor CommutePipeline:** Update `CommutePipeline.kt`. The Android app will still fetch and parse the MTA GTFS-RT feed, but instead of calling Gemini locally, it will send the structured alert text via an HTTP POST to the new Cloud Function.
* **Update Rate Limiting UI:** Remove the SharedPreferences-backed `ApiRateLimiter`. Update the client UI to catch HTTP 429 responses from the server and display the rate limit warning.

## Phase 4: Security Rules and Analytics
Secure the database and set up data export.

* **Firestore Security Rules:** Implement strict rules allowing clients to only read and update their own `users/{uid}` profile. Deny all client write access to the `usage` subcollection; only the Cloud Function may increment usage counters.
* **Analytics Aggregation:** Utilize the Firebase Console or export Firestore data to Google BigQuery to analyze total daily API volume, device type distribution (Garmin vs. Wear OS), and average requests per user.