# Concurrent Diagnostics Server

## Overview
Detailed multi-threaded HTTP server implemented in Java for serving system diagnostic data. Designed for high-reliability characterization and performance analysis.

## Key Features
* **Thread-per-Request Architecture**: Handles simultaneous diagnostic requests by spawning dedicated threads for each client connection.
* **Data Integrity**: Uses robust TCP/IP socket programming to ensure reliable transmission of system status reports.
* **Resource Management**: Optimized thread lifecycles to prevent memory leaks in persistent hardware diagnostic environments.

## Technical Stack
* **Language**: Java
* **Protocols**: HTTP, TCP/IP: HTTP, TCP/IP

# Orginial README from 244P 4.1
*cd ex4.1
*javac *.java
*java WebServer
