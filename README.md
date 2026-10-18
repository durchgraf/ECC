# ECC — Camera Viewer

A simple viewer for the **MS5 / Soulear Wi-Fi microscope**. Use the desktop version in a browser or install the standalone Android app to view and control the camera directly from your phone.

> This project is an independent community tool. It is not affiliated with or endorsed by the camera manufacturer.

## What you can do

- **View live video** in your web browser.
- **Mirror the image** when the camera view is reversed.
- **Align the image** and calibrate which direction should be down.
- **Take photos**, saved as JPEG files (in the browser or your Android gallery).
- **Record video**, saved to your device (MP4 on Android, WebM in the desktop browser).

## Get started

### You’ll need

- An MS5 / Soulear Wi-Fi microscope.
- A computer with Python 3.7 or newer.
- A web browser.

No extra Python packages or internet connection are needed to run ECC.

### Desktop viewer

1. Turn on the microscope and connect your computer to its Wi-Fi network. The network name is usually printed on the device or shown in its companion app.
2. Download or clone this project.
3. Start ECC using the launcher for your computer:

   - **Windows:** double-click `start.bat`.
   - **Linux:** open a terminal in the project folder and run `./start.sh`.

4. Open **http://127.0.0.1:45100** in your browser.

Keep the window open while you use the viewer. To close ECC, return to the window and press **Ctrl+C**.

> The microscope’s Wi-Fi network may not provide internet access. That is normal: your computer connects directly to the camera.

### Android app

The Android app connects directly to the microscope. You do not need a computer or Python. It requires **Android 10 or newer**.

1. Open this project’s **Releases** page and download the latest `ECC_VX.X.X.apk` file.
2. Open the downloaded APK and tap **Install**. If Android asks, allow your browser or file manager to install apps, then return and tap **Install** again.
3. Connect your phone to the microscope’s Wi-Fi network in Android Settings.
4. Open ECC and tap **Connect**. Photos and videos are saved to your phone’s gallery.

Only download APK files from this project’s official Releases page. The Releases page may not have a downloadable app until a release has been published.

## Using the viewer

- **Mirror** flips the image horizontally.
- **Calibrate this as down**: hold the camera in the orientation you want to count as upright, then tap or click this button.
- **Align** turns image alignment on or off. The alignment reference lasts until you reload the page.
- **Take photo** saves the current view as a JPEG (downloads it on desktop, saves it to the gallery on Android).
- **Start recording / Stop recording** saves a video when stopped (MP4 on Android, WebM in the desktop browser).

Photos and recordings include the current mirroring and alignment. Desktop-browser recording availability depends on browser support.

## If something isn’t working

- **The page opens, but there is no picture:** check that your computer is still connected to the microscope’s Wi-Fi network, then restart ECC.
- **The camera cannot be reached:** make sure it is turned on and connected to the same Wi-Fi network as your computer.
- **The alignment controls are unavailable:** wait for the live picture and orientation data to appear. Hold the camera at a different angle if it is pointing almost straight up or down.
- **Recording does not start:** try a recent version of Firefox, Chrome, or another browser that supports WebM recording.
- **The page does not open:** confirm that ECC is still running in the terminal and try http://127.0.0.1:45100 again.

## Compatibility

ECC is developed and tested for the MS5 / Soulear Wi-Fi microscope. Other cameras using a similar Wi-Fi camera system may work, but have not been confirmed.

## Credits and license

ECC is based on [MS5 WiFi Microscope by Fyfar](https://github.com/Fyfar/ms5-wifi-microscope), whose open-source camera viewer provided the starting point for this project. ECC builds on that work with its own interface and alignment, photo, and recording features. This project is distributed under the GNU Affero General Public License v3.0 (AGPL-3.0); see [LICENSE](LICENSE).

We also thank [Sean Pesce](https://github.com/SeanPesce) for [Suear-Web-Viewer](https://github.com/SeanPesce/Suear-Web-Viewer), whose work on compatible camera protocols helped make projects like this possible.

Camera names and trademarks belong to their respective owners.
