# Internal Testing Setup Plan: Commute Buddy

## Objective

Distribute the Commute Buddy app suite (Android companion app, Wear OS app, and Garmin app) to 5 beta testers to validate the MVP. This bypasses public app store review processes and minimum tester requirements.

## Prerequisite: Create a Cryptographic Keystore

You must sign both the Android and Wear OS apps with the same digital key. You only need to create this once.

1. In Android Studio, click **Build** > **Generate Signed Bundle / APK...** from the top menu.
2. Select **Android App Bundle** and click **Next**.
3. Under *Key store path*, click **Create new...**
4. Click the folder icon to choose a save location. **Crucial:** Save this file (e.g., `upload-keystore.jks`) somewhere safe on your computer, *outside* of your Git repository so you do not accidentally commit it.
5. Create a strong password for the Keystore.
6. Under *Key*, set the Alias to `upload` and create a Key password (you can use the same password as the keystore). Set Validity to `25` years.
7. Fill in your First and Last Name in the Certificate section. The other fields are optional.
8. Click **OK**. You will use this keystore for both the phone and watch apps.

## Part 1: Android Companion App (Google Play Console)

1. **Build the Release Bundle:**
  - In the Generate Signed Bundle wizard (continuing from above), verify the module dropdown is set to `app`.
  - Ensure your keystore path, alias, and passwords are filled in. Click **Next**.
  - Select the **release** build variant.
  - Click **Finish**. Android Studio will generate the file at `android/app/release/app-release.aab`.
2. **Create the App in Play Console:**
  - Log into the Google Play Console and click **Create app**.
  - Name it "Commute Buddy", select App (not Game), Free, and accept the declarations.
3. **Configure Internal Testing:**
  - In the left menu, scroll down to **Testing** > **Internal testing**.
  - Click the **Testers** tab. Click **Create email list**, name it "Beta Testers", add the 5 Google Account email addresses, and save.
  - Check the box next to your new list to assign them to this track. Click **Save**.
4. **Upload and Roll Out:**
  - Click the **Releases** tab (still under Internal testing) and click **Create new release**.
  - Upload your `app-release.aab` file.
  - Add release notes, click **Next**, and then **Save and publish**.
  - Return to the **Testers** tab, click **Copy link** under "How testers join your test", and save this URL.

## Part 2: Wear OS App (Google Play Console)

1. **Enable Wear OS Form Factor:**
  - In the Play Console left menu, go to **Release** > **Setup** > **Advanced settings**.
  - Click the **Form factors** tab.
  - Click **+ Add form factor** and select **Wear OS**.
2. **Build the Release Bundle:**
  - In Android Studio, click **Build** > **Generate Signed Bundle / APK...**
  - Select **Android App Bundle** and click **Next**.
  - Change the Module dropdown to `wear`.
  - Use the *exact same* keystore path, alias, and passwords you created in the prerequisite. Click **Next**.
  - Select the **release** build variant and click **Finish**. The file will generate at `android/wear/release/wear-release.aab`.
3. **Upload to Wear OS Track:**
  - In the Play Console, navigate back to **Testing** > **Internal testing**.
  - Near the top right of the page, click the form factor dropdown (it likely says "Standard") and change it to **Wear OS**.
  - Click **Create new release**.
  - Upload your `wear-release.aab` file, advance through the prompts, and click **Save and publish**.
  - *Note: The testers will use the same opt-in link generated in Part 1.*

## Part 3: Garmin Watch App (Connect IQ Developer Portal)
Garmin's "Beta App" feature does not allow external testers. To distribute the app over-the-air, you must publish it to the public store with a "Beta" label.

1. **Export the App:**
   * Open the `garmin/` directory in VS Code.
   * Open the Command Palette (`Ctrl+Shift+P` or `Cmd+Shift+P`).
   * Type and select `Monkey C: Export Project`.
   * This generates a `.iq` file located in `garmin/bin/`.
2. **Upload to Developer Dashboard:**
   * Log in to the [Garmin Connect IQ Developer Dashboard](https://developer.garmin.com/connect-iq/overview/).
   * Click **Upload an App**.
   * Drag and drop your `.iq` file.
3. **Configure the Listing (DO NOT check Beta):**
   * **Do not** check the "Beta App" box. 
   * Set the Title to: `Commute Buddy (Closed Beta)`
   * In the Description, write: *"Internal beta test for the Commute Buddy Android app. This watch app requires the Android companion app to function. Do not download unless you are part of the beta group."*
4. **Wait for Approval:**
   * Submit the app. Garmin's approval process typically takes 1 to 3 days. Once approved, you will receive an email and a live store link.

## Part 4: Tester Onboarding Strategy
Wait until the Garmin app is approved before sending this email to your 5 testers so nobody is waiting on a dead link.

Draft an email containing:
1. **The Google Play Link:** The Internal Testing opt-in link copied from Part 1. 
   * *Instructions:* Tap the link to opt in, then download the phone app. If you use a Wear OS watch, open the Play Store on your watch, scroll down to "Apps on your phone," and install it there.
2. **The Garmin Link:** The public Connect IQ store link generated after Garmin's approval in Part 3.
   * *Instructions:* If you use a Garmin watch, tap this link on your phone. It will open the Connect IQ app so you can install the watch face.
3. **Initial Setup Instructions:** Briefly explain how to open the Android app and configure their commute profile (legs and directions) so the background service can begin pushing MTA data to their watch.