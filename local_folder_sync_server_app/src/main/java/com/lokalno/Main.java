package com.lokalno;

import com.lokalno.config.AppConfig;
import com.lokalno.config.ConfigUtil;
import com.lokalno.util.FileSystemUtil;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.prefs.Preferences;

import org.aeonbits.owner.ConfigFactory;

import javax.imageio.ImageIO;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import javax.swing.*;


public class Main {

    private static JmDNS jmdns;
    private static final String PREF_START_MINIMIZED = "start_minimized";
    private static final String PREF_START_WITH_WINDOWS = "start_with_windows";
    private static final String PREF_SYNC_PATH = "sync_folder_path";
    private static final Preferences prefs = Preferences.userNodeForPackage(Main.class);
    private static BlockingQueue<Path> filePaths;
    private static Thread watcherThread;

    static void main(String[] args)  {
        AppConfig config = ConfigFactory.create(AppConfig.class);
        config.list(System.out);

        Path targetDirectoryPath = null;
        String savedPath = getSavedSyncPath();

        if(savedPath != null) {
            try {
                targetDirectoryPath =
                        FileSystemUtil.createTargetDirectory(savedPath);
            } catch (IOException e) {
                System.out.println("Could not create essentially needed server target directory. " + e.getMessage());
                throw new RuntimeException();
            }
        }


        filePaths = new LinkedBlockingQueue<>();
        FileSystemUtil.TlsCredentials tlsCredentials = null;
        try {
            // 1. Load TLS configuration first
            tlsCredentials = ConfigUtil.loadTlsConfig(config);

            // 2. Start gRPC server and other core components
            // ...

            if(targetDirectoryPath != null) {
                watcherThread = new Thread(new FolderWatcher(targetDirectoryPath, path -> {
                    boolean _ = filePaths.offer(path);
                }));
                watcherThread.setDaemon(true);
                watcherThread.start();
            }
            // 3. Start background workers ONLY after initialization succeeds


        } catch (Exception e) {
            System.err.println("Fatal error during initialization: " + e.getMessage());
            System.exit(1); // Ensures clean process termination regardless of running threads
        }


        SslContext sslContext;
        try {
            sslContext = GrpcSslContexts.configure(
                    SslContextBuilder.forServer(tlsCredentials.cert(), tlsCredentials.key())
                            .trustManager(tlsCredentials.ca())
                    //.clientAuth(ClientAuth.REQUIRE)
            ).build();
        }
        catch (Exception e) {
            System.err.println("Failed to build TLS certificates: " + e.getMessage());
            throw new RuntimeException();
        }

        boolean startMinimized = false;
        for (String arg : args) {
            if ("--minimized".equalsIgnoreCase(arg)) {
                startMinimized = true;
                break;
            }
        }

        if (!startMinimized) {
            startMinimized = prefs.getBoolean(PREF_START_MINIMIZED, false);
        }

        final boolean launchInTray = startMinimized;
        boolean isHeadless = GraphicsEnvironment.isHeadless();
        String pairingToken = StorageManager.getOrGeneratePairingToken();

        if (!isHeadless) {
            SwingUtilities.invokeLater(() -> createAndShowGUI(pairingToken, launchInTray));
        }

        AuthInterceptor authInterceptor = new AuthInterceptor(pairingToken);
        FolderSyncServer.FolderSyncServerImpl service = new FolderSyncServer.FolderSyncServerImpl(filePaths, pairingToken);
        service.startMainServerWorker();

        try {
            Server grpcServer = NettyServerBuilder.forPort(config.serverPort())
                    .sslContext(sslContext)
                    .addService(ServerInterceptors.intercept(service, authInterceptor))
                    .maxInboundMessageSize(64 * 1024 * 1024)
                    .build()
                    .start();
            System.out.println("Server started on port " + config.serverPort());
            System.out.println("Pairing code:" + pairingToken);

            registerMdnsService(config.serverPort());

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down...");
                cleanup();
            }));

            grpcServer.awaitTermination();


        } catch (IOException e) {
            // Startup failed (port blocked, bad SSL certs, etc.)
            System.err.println("Fatal: Failed to start gRPC server: " + e.getMessage());
            System.exit(1); // Force terminate with error status code

        } catch (InterruptedException e) {
            // Main thread was interrupted while waiting for termination
            System.err.println("gRPC Server was interrupted: " + e.getMessage());
            Thread.currentThread().interrupt(); // Restore interrupted status
        }

    }

    private static final String APP_NAME = "Local Sync Server App";
    private static JTextField pathField;

    private static void createAndShowGUI(String pairingToken, boolean launchMinimized) {
        // 1. Build the GUI Window
        JFrame frame = new JFrame(APP_NAME);
        frame.setSize(400, 300);
        frame.setLocationRelativeTo(null); // Centers window on screen

        // IMPORTANT: Do NOT use EXIT_ON_CLOSE. Use DO_NOTHING_ON_CLOSE so we intercept 'X'
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        Image mainWindowIcon = null;
        BufferedImage trayIconImage = null;

        try {
            // Load the larger 128x128 (or 32x32) icon for the main window
            URL mainIconURL = Main.class.getClassLoader().getResource("sync-icon-128.png");
            if (mainIconURL != null) {
                mainWindowIcon = ImageIO.read(mainIconURL);
            }

            // Load the small 16x16 icon for the System Tray
            URL trayIconURL = Main.class.getClassLoader().getResource("sync-icon-16.png");
            if (trayIconURL != null) {
                trayIconImage = ImageIO.read(trayIconURL);
            } else {
                System.err.println("Warning: sync-icon-16.png not found.");
            }

        } catch (IOException e) {
            System.err.println("Error loading icon images.");
        }

        if (mainWindowIcon != null) {
            // This sets the icon in the title bar and the Alt-Tab switcher
            frame.setIconImage(mainWindowIcon);
        }

        pathField = new JTextField();
        pathField.setEditable(false);
        pathField.setColumns(25);



        JButton browseButton = new JButton("Browse...");
        JButton defaultButton = new JButton("Use Default Desktop");

        JPanel pathPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        pathField.setAlignmentX(Component.LEFT_ALIGNMENT);
        browseButton.setAlignmentX(Component.RIGHT_ALIGNMENT);
        pathPanel.add(pathField);
        pathPanel.add(browseButton);

        pathPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        defaultButton.setAlignmentX(Component.CENTER_ALIGNMENT);

        // UI Layout: Display App Name and Pairing Code in the center
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JLabel titleLabel = new JLabel("<html><p style='font-size:14px;'>Pairing Code: <b style='color:red;'>"
                + pairingToken
                + "</b></p></html>");
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JCheckBox chkMinimized = new JCheckBox("Start Minimized to System Tray");
        chkMinimized.setSelected(prefs.getBoolean(PREF_START_MINIMIZED, false));
        chkMinimized.setAlignmentX(Component.CENTER_ALIGNMENT);
        chkMinimized.addActionListener(_ ->
            prefs.putBoolean(PREF_START_MINIMIZED, chkMinimized.isSelected())
        );

        JCheckBox chkAutoStart = new JCheckBox("Start Automatically with Windows");
        chkAutoStart.setSelected(prefs.getBoolean(PREF_START_WITH_WINDOWS, false));
        chkAutoStart.setAlignmentX(Component.CENTER_ALIGNMENT);
        chkAutoStart.addActionListener(_ -> {
            boolean enable = chkAutoStart.isSelected();
            prefs.putBoolean(PREF_START_WITH_WINDOWS, enable);
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                toggleWindowsAutostart(enable);
            } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                toggleLinuxAutostart(enable);
            } else if (os.contains("mac") || os.contains("darwin")) {
                System.setProperty("apple.laf.useScreenMenuBar", "true");

                // 2. Set application name in the macOS menu bar
                System.setProperty("apple.awt.application.name", "LocalSyncServerApp");

                // 3. Make Swing UI match native macOS controls (FlatLaf or System Look)
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {}
                toggleMacAutostart(enable);
            }
        });

        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(15));
        panel.add(pathPanel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(defaultButton);
        panel.add(Box.createVerticalStrut(5));
        panel.add(chkMinimized);
        panel.add(Box.createVerticalStrut(5));
        panel.add(chkAutoStart);
        frame.add(panel);

        if (SystemTray.isSupported()) {
            SystemTray tray = SystemTray.getSystemTray();

            // Placeholder icon image
            if (trayIconImage == null) {
                System.err.println("Using blank placeholder tray icon.");
                trayIconImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = trayIconImage.createGraphics();
                g.setColor(Color.BLUE);
                g.fillOval(4,4,8,8);
                g.dispose();
            }

            // Context Menu for System Tray
            PopupMenu popup = new PopupMenu();

            MenuItem showItem = new MenuItem("Open Window");
            showItem.addActionListener(_ -> {
                frame.setVisible(true);
                frame.setState(Frame.NORMAL); // Un-minimize if minimized
            });

            MenuItem exitItem = new MenuItem("Exit Server");
            exitItem.addActionListener(_ -> System.exit(0));

            MenuItem codeItem = new MenuItem("Pairing code: " + pairingToken);


            popup.add(showItem);
            popup.add(codeItem);
            popup.addSeparator();
            popup.add(exitItem);

            TrayIcon trayIcon = new TrayIcon(trayIconImage, APP_NAME, popup);
            trayIcon.setImageAutoSize(true);

            // Restore window when double-clicking the tray icon
            trayIcon.addActionListener(_ -> {
                frame.setVisible(true);
                frame.setState(Frame.NORMAL);
            });

            try {
                tray.add(trayIcon);
            } catch (AWTException e) {
                System.err.println("Could not add System Tray icon.");
            }

            // Intercept window close (X button) -> Hide to tray instead of quitting
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    frame.setVisible(false); // Hide window, keep app running in tray
                }

                @Override
                public void windowIconified(WindowEvent e) {
                    // Optional: also hide to tray when pressing the built-in minimize button (_)
                    frame.setVisible(false);
                }
            });

            if (!launchMinimized) {
                frame.setVisible(true);
            } else {
                System.out.println("App launched hidden in system tray.");
            }
        } else {
            // System Tray isn't available on this OS; fallback to closing app on 'X'
            frame.setVisible(true);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        }

        // Show window on startup
        //frame.setVisible(true);
        browseButton.addActionListener(_ -> chooseFolder());
        defaultButton.addActionListener(_ -> createAndSetDefaultDesktopFolder());

        // Initialize: Load saved path OR auto-create Desktop default


        String savedPath = getSavedSyncPath();

        if (savedPath != null && new File(savedPath).exists()) {
            // Option A: Path already exists in Preferences
            pathField.setText(savedPath);
        }
    }

    private static void toggleMacAutostart(boolean enable) {
        Path launchAgentsDir = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents");
        String label = "com.lokalno.localsyncserverapp";
        File plistFile = launchAgentsDir.resolve(label + ".plist").toFile();

        if (enable) {
            try {
                Files.createDirectories(launchAgentsDir);

                // Get path to packaged macOS .app bundle
                String appPath = System.getProperty("jpackage.app-path");
                if (appPath == null || appPath.isEmpty()) {
                    appPath = "/Applications/LocalSyncServerApp.app/Contents/MacOS/LocalSyncServerApp";
                }

                try (PrintWriter writer = new PrintWriter(new FileWriter(plistFile))) {
                    writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
                    writer.println("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">");
                    writer.println("<plist version=\"1.0\">");
                    writer.println("<dict>");
                    writer.println("    <key>Label</key>");
                    writer.println("    <string>" + label + "</string>");
                    writer.println("    <key>ProgramArguments</key>");
                    writer.println("    <array>");
                    writer.println("        <string>" + appPath + "</string>");
                    writer.println("        <string>--minimized</string>");
                    writer.println("    </array>");
                    writer.println("    <key>RunAtLoad</key>");
                    writer.println("    <true/>");
                    writer.println("</dict>");
                    writer.println("</plist>");
                }
            } catch (Exception e) {
                System.out.println("Could not write startup list. " + e.getMessage());
            }
        } else {
            if (plistFile.exists()) {
                boolean _ = plistFile.delete();
            }
        }
    }

    private static void toggleLinuxAutostart(boolean enable) {
        Path autostartDir = Path.of(System.getProperty("user.home"), ".config", "autostart");
        File desktopFile = autostartDir.resolve("localsyncserver.desktop").toFile();

        if (enable) {
            try {
                Files.createDirectories(autostartDir);
                String appPath = System.getProperty("jpackage.app-path");

                // Fallback if running outside jpackage binary
                if (appPath == null || appPath.isEmpty()) {
                    appPath = "/opt/localsyncserverapp/bin/LocalSyncServerApp";
                }

                try (PrintWriter writer = new PrintWriter(new FileWriter(desktopFile))) {
                    writer.println("[Desktop Entry]");
                    writer.println("Type=Application");
                    writer.println("Name=LocalSyncServerApp");
                    writer.println("Exec=" + appPath + " --minimized");
                    writer.println("Terminal=false");
                    writer.println("X-GNOME-Autostart-enabled=true");
                }
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
        } else {
            if (desktopFile.exists()) {
                boolean success = desktopFile.delete();
                if(success) {
                    System.out.println("Successful deletion.");
                }
                else {
                    System.out.println("Failed deletion.");
                }

            }
        }
    }

    public static String getSavedSyncPath() {
        return prefs.get(PREF_SYNC_PATH, null);
    }

    private static void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Directory to Watch for Sync");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);

        // Set initial directory to currently selected folder if valid
        String currentPath = pathField.getText();
        if (!currentPath.isEmpty()) {
            File currentDir = new File(currentPath);
            if (currentDir.exists()) {
                chooser.setCurrentDirectory(currentDir);
            }
        }

        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFolder = chooser.getSelectedFile();
            saveAndDisplayPath(selectedFolder.getAbsolutePath());
        }
    }

    private static void createAndSetDefaultDesktopFolder() {
        Path defaultFolderPath = Path.of(System.getProperty("user.home"), "Desktop", "LocalSyncFolder");
        File defaultFolder = new File(defaultFolderPath.toString());

        if (!defaultFolder.exists()) {
            boolean created = defaultFolder.mkdirs();
            if (!created) {
                JOptionPane.showMessageDialog(null,
                        "Failed to create default folder on Desktop.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        saveAndDisplayPath(defaultFolder.getAbsolutePath());
    }

    /**
     * Updates UI and persists path to Windows Registry / OS Preferences.
     */
    private static void saveAndDisplayPath(String absolutePath) {
        prefs.put(PREF_SYNC_PATH, absolutePath);
        pathField.setText(absolutePath);

        // Notify file watcher system here if applicable
        onSyncPathChanged(absolutePath);
    }

    private static void onSyncPathChanged(String newPath) {
        System.out.println("Sync folder path updated to: " + newPath);
        if (watcherThread != null) {
            watcherThread.interrupt();
        }

        Path targetDirectoryPath;
        try {
            targetDirectoryPath =
                    FileSystemUtil.createTargetDirectory(newPath);
            watcherThread = new Thread(new FolderWatcher(targetDirectoryPath, path -> {
                boolean _ = filePaths.offer(path);
            }));
            watcherThread.setDaemon(true);
            watcherThread.start();
        } catch (IOException e) {
            throw new RuntimeException("Could not create essentially needed server target directory.");
        }
    }

    private static void toggleWindowsAutostart(boolean enable) {
        try {
            // Find path to executable (Works both for running .exe installers and executable .jar files)


            String regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

            File jarFile = new File(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());

            // Check if we are inside a jpackage structure (jar is in an 'app' folder)
            File appFolder = jarFile.getParentFile(); // C:\Program Files\LocalSyncServerApp\app
            File installFolder = appFolder != null ? appFolder.getParentFile() : null; // C:\Program Files\LocalSyncServerApp

            // Expected EXE path in the installation folder
            File exeFile = (installFolder != null)
                    ? new File(installFolder, "LocalSyncServerApp.exe")
                    : null;

            String runCommand;

            if (enable) {
                // Determine run command: If packaged as EXE, run EXE. If JAR, use javaw.


                if (exeFile != null && exeFile.exists()) {
                    // We are running inside the packaged EXE!
                    runCommand = exeFile.getAbsolutePath() + " --minimized";
                } else {
                    // We are running in IDE / standalone JAR
                    String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "javaw.exe";
                    runCommand = javaBin + " -jar " + jarFile.getAbsolutePath() + " --minimized";
                }



                // Add entry to registry via 'reg add'
                ProcessBuilder pb = new ProcessBuilder(
                        "reg", "add", regKey, "/v", APP_NAME, "/t", "REG_SZ", "/d", runCommand, "/f"
                );


                pb.inheritIO();

                // 2. Start process
                Process p = pb.start();

                // 3. Force Java to wait until reg.exe completes
                int exitCode = p.waitFor();

                if (exitCode == 0) {
                    System.out.println("Registry successfully updated!");
                } else {
                    System.err.println("Registry update failed with exit code: " + exitCode);
                }
                //System.out.println("Added to Windows Autostart.");
            } else {
                // Remove entry from registry via 'reg delete'
                ProcessBuilder pb = new ProcessBuilder(
                        "reg", "delete", regKey, "/v", APP_NAME, "/f"
                );
                pb.start();

                pb.inheritIO();

                // 2. Start process
                Process p = pb.start();

                // 3. Force Java to wait until reg.exe completes
                int exitCode = p.waitFor();

                if (exitCode == 0) {
                    System.out.println("Registry successfully deleted!");
                } else {
                    System.err.println("Registry deletion failed with exit code: " + exitCode);
                }

            }
        } catch (Exception e) {
            System.err.println("Failed to modify Windows Registry for autostart: " + e.getMessage());
        }
    }

    private static void registerMdnsService(int port) {
        try {
            // Get the local IP address of your PC on the local network
            InetAddress serverIP = null;
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface netInterface = interfaces.nextElement();

                    // Skip loopback (127.0.0.1) and inactive/virtual cards
                    if (netInterface.isLoopback() || !netInterface.isUp() || netInterface.isVirtual()) {
                        continue;
                    }

                    Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        // We want a standard IPv4 address matching your local network (e.g., 192.168.x.x)
                        if (addr instanceof java.net.Inet4Address) {
                            serverIP = addr;
                            break;
                        }
                    }
                    if (serverIP != null) break;
                }
            } catch (Exception e) {
                System.out.println("Could not get IP address");
            }

            if (serverIP == null) {
                // Fallback if discovery fails
                serverIP = InetAddress.getLocalHost();
            }

            System.out.println("Binding mDNS explicitly to local IP: " + serverIP.getHostAddress());
            jmdns = JmDNS.create(serverIP);

            // Define the service: Service Type, Service Name, Port, and description/properties
            String serviceType = "_grpc._tcp.local.";

            String user = System.getProperty("user.name", "UnknownUser");
            String computer = null;// = "UnknownHost";


            // Fallback for macOS if local hostname isn't resolved
            String envHost = System.getenv("HOSTNAME");
            if (envHost == null || envHost.isEmpty()) {
                envHost = System.getenv("COMPUTERNAME"); // Windows fallback
            }
            if (envHost != null && !envHost.isEmpty()) {
                computer = envHost;
            }

            if (computer == null || computer.isEmpty() || computer.equalsIgnoreCase("localhost")) {
                try {
                    Process process = Runtime.getRuntime().exec("hostname");
                    try (java.util.Scanner scanner = new java.util.Scanner(process.getInputStream())) {
                        if (scanner.hasNext()) {
                            computer = scanner.next().trim();
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore if terminal execution fails
                }
            }

            // Fallback C: Ultimate default if everything fails
            if (computer == null || computer.isEmpty()) {
                computer = "Host";
            }

            // 3. Clean up formatting for mDNS safety
            // Remove trailing domain names like ".local" or ".lan"
            if (computer.contains(".")) {
                computer = computer.substring(0, computer.indexOf("."));
            }

            // Replace any remaining invalid mDNS characters with hyphens
            user = user.replaceAll("[^a-zA-Z0-9_-]", "");
            computer = computer.replaceAll("[^a-zA-Z0-9_-]", "");

            String serviceName = user + "_" + computer;

            ServiceInfo serviceInfo = ServiceInfo.create(serviceType, serviceName, port, "gRPC service on local network");

            // Optional: You can attach metadata (like service version or custom paths)
            serviceInfo.setText(java.util.Map.of("version", "1.0.0"));

            jmdns.registerService(serviceInfo);
            System.out.println("mDNS: Registered service '" + serviceName + "' under type '" + serviceType + "'");
        } catch (IOException e) {
            System.err.println("Failed to initialize mDNS: " + e.getMessage());
        }
    }

    private static void cleanup() {
        if (jmdns != null) {
            jmdns.unregisterAllServices();
            try {
                jmdns.close();
            } catch (IOException e) {
                System.out.println("Failed to close jmdns " + e.getMessage());
            }
        }

    }

}