import swiftbot.Button;
import swiftbot.SwiftBotAPI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Scans QR codes containing hexadecimal values and uses them
 * to generate and perform SwiftBot dance routines.
 */
public class SwiftBotDanceRoutine {
    private static final String RESET = "\u001B[0m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String WHITE = "\u001B[37m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIVIDER = "--------------------------------------------------------";

    /** SwiftBot API instance used for hardware control. */
    private static final SwiftBotAPI api = SwiftBotAPI.INSTANCE;
    /** Maximum motor speed allowed for movement. */
    private static final int MAX_SPEED = 100;
    /** Fixed duration used for spin moves. */
    private static final int SPIN_DURATION_MS = 500;
    /** Maximum number of values taken from one QR code. */
    private static final int MAX_QR_VALUES = 5;
    /** Time allowed for a Y or X button press. */
    private static final int BUTTON_TIMEOUT_MS = 25000;
    /** File used to save accepted hexadecimal values. */
    private static final String LOG_FILE_NAME = "swiftbot_hex_log.txt";

    /** Tracks whether button Y has been pressed. */
    private static volatile boolean yPressed = false;
    /** Tracks whether button X has been pressed. */
    private static volatile boolean xPressed = false;
    /** Ensures spin direction is chosen once per session. */
    private static boolean spinDirectionChosen = false;
    /** Stores the spin direction for the whole session. */
    private static boolean spinClockwise = true;

    /** Possible menu choices from the SwiftBot buttons. */
    private enum ButtonChoice {
        Y, X, ERROR
    }

    /**
     * Starts the program and ensures the log is saved before exit.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) 
    {
        ArrayList<String> allAcceptedHexValues = new ArrayList<>();

        try 
        {
            if (promptToBeginScanning()) 
            {
                mainLoop(allAcceptedHexValues);
            } 
            else 
            {
                System.out.println("User chose not to begin scanning.");
            }
        } 
        catch (Exception e) 
        {
            printErrorBox("A fatal error occurred.\nThe program will now save the session log and shut down safely.");
        } 
        finally 
        {
            saveAndSortHexLog(allAcceptedHexValues);
            cleanupSwiftBot();
            System.out.println();
            System.out.println(GREEN + BOLD + "Program terminated." + RESET);
            System.exit(0);
        }
    }

    /**
     * Repeats scanning, validation and dance execution until the user exits.
     *
     * @param allAcceptedHexValues: stores all valid values for the session log
     */
    public static void mainLoop(ArrayList<String> allAcceptedHexValues) {
        while (true) {
            String qrInput = scanQRCode();
            if (qrInput == null) {
                printErrorBox("Maximum scan attempts reached for this QR scan.");
                if (askScanAgainOrExit() != ButtonChoice.Y) {
                    return;
                }
                continue;
            }

            String[] splitValues = splitHexValues(qrInput);
            ArrayList<String> ignoredValues = new ArrayList<>();
            ArrayList<String> validValues = validateHexValues(splitValues, ignoredValues);
            printScanResultScreen(qrInput, validValues, ignoredValues);

            if (validValues.isEmpty()) {
                printErrorBox("No valid hexadecimal values were found in the QR code.");
                if (askScanAgainOrExit() != ButtonChoice.Y) {
                    return;
                }
                continue;
            }

            if (!spinDirectionChosen) {
                chooseSpinDirection();
            }

            for (int i = 0; i < validValues.size(); i++) {
                String hex = validValues.get(i);
                allAcceptedHexValues.add(hex);

                try {
                    DanceProfile profile = buildDanceProfile(hex, MAX_SPEED);
                    executeDanceRoutine(profile, i + 1, validValues.size());
                } catch (Exception e) {
                    printErrorBox("The value '" + hex + "' could not be processed and was skipped.");
                }
            }

            if (askScanAgainOrExit() != ButtonChoice.Y) {
                return;
            }
        }
    }

    /**
     * Prompts the user to scan again or exit.
     *
     * @return the selected button choice
     */
    public static ButtonChoice askScanAgainOrExit() 
    {
        System.out.println("Press Y to scan another QR code.");
        System.out.println("Press X to exit the program.");
        return waitForYOrXPress();
    }

    /**
     * Shows the welcome screen before scanning starts.
     *
     * @return true if the user chooses to begin scanning
     */
    public static boolean promptToBeginScanning() 
    {
        printScreenHeader("BEGIN QR SCANNING");
        System.out.printf("%sWelcome to the SwiftBot Dance Routine System.%s%n", WHITE, RESET);
        System.out.printf("%sAPI Version: %s%s%n%n", WHITE, api.getVersion(), RESET);
        System.out.println("Press Y to begin scanning a QR code.");
        System.out.println("Press X to exit the program.");
        printScreenFooter();
        return waitForYOrXPress() == ButtonChoice.Y;
    }

    /**
     * Displays the scan result, including valid and ignored values.
     *
     * @param rawInput: the original QR code text
     * @param validValues: accepted hexadecimal values
     * @param ignoredValues: rejected values
     */
    public static void printScanResultScreen(String rawInput,
                                             ArrayList<String> validValues,
                                             ArrayList<String> ignoredValues) 
    {
        printScreenHeader("QR SCAN AND VALIDATION RESULT");
        System.out.println("QR code detected: " + rawInput);
        System.out.println();
        System.out.println("Accepted hexadecimal values:");

        if (validValues.isEmpty()) 
        {
            System.out.println("- None");
        } 
        else 
        {
            for (String value : validValues) 
            {
                System.out.println("- " + value);
            }
        }

        if (!ignoredValues.isEmpty()) 
        {
            System.out.println();
            System.out.println(RED + "Ignored invalid values:" + RESET);
            for (String value : ignoredValues) 
            {
                System.out.println("- " + value);
            }
            System.out.println();
            System.out.println("Use only 1 or 2 hexadecimal digits per value (0-9 or A-F).");
        }
        
        printScreenFooter();
    }

    /**
     * Displays the screen for choosing spin direction.
     */
    public static void printSpinDirectionScreen() 
    {
        printScreenHeader("SPIN DIRECTION SELECTION");
        System.out.println("Before the first dance routine, choose the direction");
        System.out.println("for all spinning movements in this session.");
        System.out.println();
        System.out.println("Press Y for CLOCKWISE");
        System.out.println("Press X for ANTICLOCKWISE");
    }

    /**
     * Displays the details of the current dance routine.
     *
     * @param profile: the data used for the routine
     * @param current: current value number being processed
     * @param total: total number of values in this scan
     */
    public static void printExecutionScreen(DanceProfile profile, int current, int total) 
    {
        printScreenHeader("DANCE EXECUTION AND PROGRESS");
        System.out.println("Processing value " + current + " of " + total);
        System.out.println();
        System.out.println("Hexadecimal value: " + profile.hex);
        System.out.println("Decimal value: " + profile.decimal);
        System.out.println("Octal value: " + profile.octal);
        System.out.println("Binary value: " + profile.binary);
        System.out.println("Speed: " + profile.speed);
        System.out.println("LED colour: red " + profile.red
                + ", green " + profile.green
                + ", blue " + profile.blue);
        System.out.println("Spin direction: " + (spinClockwise ? "CLOCKWISE" : "ANTICLOCKWISE"));
        System.out.println("Forward duration: " + (profile.forwardDurationMs / 1000.0) + " seconds");
        System.out.println();
        System.out.println("Dance steps:");
    }

    /**
     * Displays the final log save message.
     *
     * @param absolutePath: full path of the saved file
     * @param count number: of saved values
     */
    public static void printLogSavedScreen(String absolutePath, int count) 
    {
        printScreenHeader("PROGRAM TERMINATION");
        System.out.println("Session complete.");
        System.out.println("Accepted hexadecimal values saved: " + count);
        System.out.println("Log file path: " + absolutePath);
        printScreenFooter();
    }

    /**
     * Prints a formatted screen header.
     *
     * @param title title shown at the top of the screen
     */
    public static void printScreenHeader(String title) 
    {
        System.out.println();
        System.out.println(CYAN + BOLD + DIVIDER + RESET);
        System.out.println(CYAN + BOLD + title + RESET);
        System.out.println(CYAN + BOLD + DIVIDER + RESET);
    }

    /**
     * Prints a formatted screen footer.
     */
    public static void printScreenFooter() 
    {
        System.out.println(CYAN + BOLD + DIVIDER + RESET);
        System.out.println();
    }

    /**
     * Prints a formatted error message.
     *
     * @param message: error text to display
     */
    public static void printErrorBox(String message) 
    {
        System.out.println();
        System.out.println(RED + BOLD + DIVIDER + RESET);
        System.out.println(RED + BOLD + "ERROR" + RESET);
        System.out.println(RED + BOLD + DIVIDER + RESET);
        System.out.println(message);
        System.out.println(RED + BOLD + DIVIDER + RESET);
        System.out.println();
    }

    /**
     * Attempts to scan and decode a QR code.
     *
     * @return the QR text in uppercase, or null if scanning fails repeatedly
     */
    public static String scanQRCode() 
    {
        int attempts = 0;
        int maxAttempts = 10;

        // Keep retrying until a valid QR code is read or the limit is reached.
        while (attempts < maxAttempts) 
        {
            attempts++;

            try 
            {
                System.out.println(BOLD + "Please position a QR code in front of the SwiftBot camera..." + RESET);
                System.out.println("Attempt " + attempts + " of " + maxAttempts);

                BufferedImage qrImage = api.getQRImage();
                if (qrImage == null) 
                {
                    printErrorBox("The camera did not capture an image.\nPlease reposition the QR code and try again.");
                    continue;
                }

                String qrText = api.decodeQRImage(qrImage);
                if (qrText == null || qrText.trim().isEmpty()) 
                {
                    printErrorBox("No QR code was detected.\nPlease make sure the QR code is visible and try again.");
                    continue;
                }

                return qrText.trim().toUpperCase();
            } 
            catch (IllegalArgumentException e) 
            {
                printErrorBox("QR decoding failed.\nPlease make sure the QR code is clear and fully visible.");
            } 
            catch (Exception e) 
            {
                printErrorBox("An unexpected scanning error occurred.\nPlease try scanning the QR code again.");
            }
        }

        return null;
    }

    /**
     * Lets the user choose the spin direction for the session.
     */
    public static void chooseSpinDirection() 
    {
        printSpinDirectionScreen();
        ButtonChoice choice = waitForYOrXPress();

        if (choice == ButtonChoice.ERROR) 
        {
            printErrorBox("No valid button choice was made.\nProgram will now terminate safely.");
            throw new RuntimeException("Spin direction was not selected.");
        }

        spinClockwise = choice == ButtonChoice.Y;
        spinDirectionChosen = true;
        System.out.println();
        System.out.println("Selected spin direction: "
                + GREEN + (spinClockwise ? "CLOCKWISE" : "ANTICLOCKWISE") + RESET);
        System.out.println("This direction will be used for all spins in this session.");
        printScreenFooter();
    }

    /**
     * Waits for the user to press Y or X.
     *
     * @return Y, X, or ERROR if no safe choice is available
     */
    public static ButtonChoice waitForYOrXPress() {
        yPressed = false;
        xPressed = false;
        long start = System.currentTimeMillis();

        try 
        {
            api.setButtonLight(Button.Y, true);
            api.setButtonLight(Button.X, true);
            api.enableButton(Button.Y, () -> yPressed = true);
            api.enableButton(Button.X, () -> xPressed = true);

            // Poll until one button is pressed or the timeout is reached.
            while (!yPressed && !xPressed) 
            {
                if (System.currentTimeMillis() - start > BUTTON_TIMEOUT_MS) 
                {
                    printErrorBox("No button was pressed in time.\nUsing a safe default option.");
                    return ButtonChoice.ERROR;
                }
                Thread.sleep(100);
            }

            return yPressed ? ButtonChoice.Y : ButtonChoice.X;
        } 
        catch (InterruptedException e) 
        {
            Thread.currentThread().interrupt();
            printErrorBox("Button input was interrupted.\nThe program will continue with a safe default option.");
            return ButtonChoice.ERROR;
        } 
        catch (Exception e) 
        {
            printErrorBox("A button error occurred.\nThe program will continue with a safe default option.");
            return ButtonChoice.ERROR;
        } 
        finally 
        {
            try 
            {
                api.disableButton(Button.Y);
                api.disableButton(Button.X);
                api.setButtonLight(Button.Y, false);
                api.setButtonLight(Button.X, false);
            } 
            catch (Exception ignored) {}
        }
    }

    /**
     * Splits the QR input into separate hexadecimal values.
     *
     * @param qrInput: raw QR text
     * @return an array of values, limited to the first 5
     */
    public static String[] splitHexValues(String qrInput) 
    {
        if (qrInput == null || qrInput.trim().isEmpty()) 
        {
            return new String[0];
        }

        String[] rawParts = qrInput.split("&", -1);
        int resultSize = Math.min(rawParts.length, MAX_QR_VALUES);
        String[] result = new String[resultSize];

        if (rawParts.length > MAX_QR_VALUES) 
        {
            System.out.println(YELLOW + "Warning: more than 5 values were detected." + RESET);
            System.out.println("Only the first 5 values will be processed.");
        }

        for (int i = 0; i < resultSize; i++) 
        {
            result[i] = rawParts[i] == null ? null : rawParts[i].trim().toUpperCase();
        }

        return result;
    }

    /**
     * Separates valid hexadecimal values from ignored ones.
     *
     * @param values: values taken from the QR code
     * @param ignoredValues: stores rejected values
     * @return a list of valid hexadecimal values
     */
    public static ArrayList<String> validateHexValues(String[] values, ArrayList<String> ignoredValues) 
    {
        ArrayList<String> validValues = new ArrayList<>();
        if (values == null) 
        {
            return validValues;
        }

        for (String currentValue : values) 
        {
            if (currentValue == null) 
            {
                ignoredValues.add("[null]");
            } 
            else 
            {
                String trimmedValue = currentValue.trim();
                if (trimmedValue.isEmpty()) 
                {
                    ignoredValues.add("[empty]");
                } 
                else if (isValidHex(trimmedValue)) 
                {
                    validValues.add(trimmedValue.toUpperCase());
                } 
                else 
                {
                    ignoredValues.add(trimmedValue);
                }
            }
        }

        return validValues;
    }

    /**
     * Checks whether a value is a valid 1- or 2-digit hexadecimal number.
     *
     * @param value: hex value to be checked
     * @return true if the value is valid
     */
    public static boolean isValidHex(String value) {
        if (value == null) 
        {
            return false;
        }

        value = value.trim().toUpperCase();
        if (value.isEmpty() || value.length() > 2) 
        {
            return false;
        }

        for (int i = 0; i < value.length(); i++) 
        {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) 
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Converts one hexadecimal character to its decimal value.
     *
     * @param c: hexadecimal character
     * @return decimal value of the character
     */
    public static int hexCharToValue(char c) 
    {
        c = Character.toUpperCase(c);

        if (c >= '0' && c <= '9') 
        {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') 
        {
            return 10 + (c - 'A');
        }

        throw new IllegalArgumentException("Invalid hexadecimal character: " + c);
    }

    /**
     * Converts a hexadecimal string to decimal.
     *
     * @param hex: hexadecimal string
     * @return decimal value
     */
    public static int hexToDecimal(String hex) 
    {
        if (hex == null || hex.trim().isEmpty()) 
        {
            throw new IllegalArgumentException("Hex input cannot be null or empty.");
        }

        hex = hex.trim().toUpperCase();
        int decimal = 0;

        for (int i = 0; i < hex.length(); i++) 
        {
            int value = hexCharToValue(hex.charAt(i));
            decimal = decimal * 16 + value;
        }

        return decimal;
    }

    /**
     * Converts a decimal number to binary.
     *
     * @param decimal decimal value
     * @return binary string
     */
    public static String decimalToBinary(int decimal) 
    {
        if (decimal < 0) 
        {
            throw new IllegalArgumentException("Decimal value must be non-negative.");
        }
        if (decimal == 0) 
        {
            return "0";
        }

        StringBuilder reversed = new StringBuilder();
        while (decimal > 0) 
        {
            reversed.append(decimal % 2);
            decimal /= 2;
        }

        return reversed.reverse().toString();
    }

    /**
     * Converts a decimal number to octal.
     *
     * @param decimal: decimal value
     * @return octal string
     */
    public static String decimalToOctal(int decimal) 
    {
        if (decimal < 0) 
        {
            throw new IllegalArgumentException("Decimal value must be non-negative.");
        }
        if (decimal == 0) 
        {
            return "0";
        }

        StringBuilder reversed = new StringBuilder();
        while (decimal > 0) 
        {
            reversed.append(decimal % 8);
            decimal /= 8;
        }

        return reversed.reverse().toString();
    }

    /**
     * Converts an octal string made of digits into an integer.
     *
     * @param octal: the octal string
     * @return integer made from the octal digits
     */
    public static int octalStringToInt(String octal) {
        int result = 0;

        for (int i = 0; i < octal.length(); i++) 
        {
            result = result * 10 + (octal.charAt(i) - '0');
        }

        return result;
    }

    /**
     * Builds all values needed to perform a dance routine.
     *
     * @param hex: hexadecimal input value
     * @param maxSpeed: highest speed allowed
     * @return completed dance profile
     */
    public static DanceProfile buildDanceProfile(String hex, int maxSpeed) 
    {
        DanceProfile profile = new DanceProfile();
        profile.hex = hex.trim().toUpperCase();
        profile.decimal = hexToDecimal(profile.hex);
        profile.binary = decimalToBinary(profile.decimal);
        profile.octal = decimalToOctal(profile.decimal);
        profile.forwardDurationMs = profile.hex.length() == 1 ? 1000 : 500;
        profile.red = profile.decimal;
        profile.green = (profile.decimal % 80) * 3;
        profile.blue = Math.max(profile.red, profile.green);

        // Speed is based on the octal digits, then kept within a safe range.
        int speed = octalStringToInt(profile.octal);
        if (speed < 50) 
        {
            speed += 50;
        }
        if (speed > maxSpeed) 
        {
            speed = maxSpeed;
        }

        profile.speed = speed;
        return profile;
    }

    /**
     * Executes the dance routine for one hexadecimal value.
     *
     * @param profile: dance data for the current value
     * @param current: current value number
     * @param total: total number of values being processed
     */
    public static void executeDanceRoutine(DanceProfile profile, int current, int total) 
    {
        int[] rgb = {profile.red, profile.green, profile.blue};
        printExecutionScreen(profile, current, total);

        try 
        {
            api.fillUnderlights(rgb);

            // Read bits from right to left so each bit becomes one move in order.
            for (int i = profile.binary.length() - 1, moveNumber = 1; i >= 0; i--, moveNumber++) 
            {
                char bit = profile.binary.charAt(i);
                if (bit == '1') 
                {
                    System.out.println("Step " + moveNumber + ": MOVE FORWARD");
                    api.move(profile.speed, profile.speed, profile.forwardDurationMs);
                } 
                else 
                {
                    System.out.println("Step " + moveNumber + ": SPIN "
                            + (spinClockwise ? "CLOCKWISE" : "ANTICLOCKWISE"));
                    if (spinClockwise) 
                    {
                        api.move(profile.speed, -profile.speed, SPIN_DURATION_MS);
                    } 
                    else 
                    {
                        api.move(-profile.speed, profile.speed, SPIN_DURATION_MS);
                    }
                }
            }

            api.stopMove();
            api.disableUnderlights();
            System.out.println();
            System.out.println("Routine complete for value: " + profile.hex);
            printScreenFooter();
        } 
        catch (Exception e) 
        {
            api.stopMove();
            api.disableUnderlights();
            printErrorBox("The dance routine stopped early.\nThe SwiftBot has been safely stopped.");
        }
    }

    /**
     * Saves accepted hexadecimal values in ascending decimal order.
     *
     * @param values: accepted values from the session
     */
    public static void saveAndSortHexLog(ArrayList<String> values) 
    {
        if (values == null) 
        {
            return;
        }

        try 
        {
            ArrayList<String> cleanedValues = new ArrayList<>();
            for (String value : values) 
            {
                if (value != null && !value.trim().isEmpty()) 
                {
                    cleanedValues.add(value.trim().toUpperCase());
                }
            }

            // Sort by decimal value rather than alphabetical order.
            Collections.sort(cleanedValues, (a, b) -> Integer.compare(hexToDecimal(a), hexToDecimal(b)));

            File file = new File(LOG_FILE_NAME);
            try (FileWriter writer = new FileWriter(file)) 
            {
                for (String value : cleanedValues) 
                {
                    writer.write(value + System.lineSeparator());
                }
            }

            printLogSavedScreen(file.getAbsolutePath(), cleanedValues.size());
        } 
        catch (IOException e) 
        {
            printErrorBox("The log file could not be written.\nPlease check that the program can write to the current folder.");
        }
    }

    /**
     * Safely turns off movement, lights and buttons before exit.
     */
    public static void cleanupSwiftBot() 
    {
        try 
        {
            api.stopMove();
        } 
        catch (Exception ignored) {}

        try 
        {
            api.disableUnderlights();
        } 
        catch (Exception ignored) {}

        try 
        {
            api.disableAllButtons();
        } 
        catch (Exception ignored) {}

        try 
        {
            api.disableButtonLights();
        } 
        catch (Exception ignored) 
        {}
    }

    /**
     * Stores all calculated values for one dance routine.
     */
    private static class DanceProfile 
    {
        String hex;
        int decimal;
        String binary;
        String octal;
        int speed;
        int red;
        int green;
        int blue;
        int forwardDurationMs;
    }
}
