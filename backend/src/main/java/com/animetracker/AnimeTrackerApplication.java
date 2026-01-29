package com.animetracker;  // Package name (like a folder structure)

// Import statements: Bring in classes from other packages
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Application Class
 * This is the entry point of our Spring Boot application.
 * When you run "java -jar app.jar", this main() method executes.
 */
@SpringBootApplication  // ← Magic annotation! Does 3 things (explained below)
public class AnimeTrackerApplication {

    /**
     * Main method: Entry point of any Java application
     *
     * @param args Command-line arguments (we don't use them, but Java requires this signature)
     */
    public static void main(String[] args) {
        // SpringApplication.run() starts the entire Spring Boot application
        // It does A LOT behind the scenes (explained below)
        SpringApplication.run(AnimeTrackerApplication.class, args);
    }

}

/*
 * === DETAILED EXPLANATION ===
 *
 * @SpringBootApplication Annotation:
 *
 * This is a "meta-annotation" - it's actually 3 annotations combined:
 *
 * 1. @Configuration
 *    - Marks this class as a source of bean definitions
 *    - "Beans" are objects managed by Spring (controllers, services, repositories)
 *
 * 2. @EnableAutoConfiguration
 *    - Enables Spring Boot's auto-configuration magic
 *    - Spring Boot looks at your dependencies (pom.xml) and automatically configures them
 *    - Example: Sees PostgreSQL driver → configures database connection
 *    - Example: Sees spring-boot-starter-web → configures embedded Tomcat server
 *
 * 3. @ComponentScan
 *    - Scans this package (com.animetracker) and all subpackages for Spring components
 *    - Finds classes with @Controller, @Service, @Repository annotations
 *    - Registers them as beans so Spring can manage them
 *
 * What happens when SpringApplication.run() executes:
 *
 * 1. Creates application context (the "container" that manages all beans)
 * 2. Scans for components (controllers, services, etc.)
 * 3. Auto-configures based on dependencies:
 *    - Sees spring-boot-starter-web → starts embedded Tomcat on port 8080
 *    - Sees spring-boot-starter-data-jpa → configures Hibernate and database connection
 *    - Sees flyway-core → runs database migrations
 * 4. Starts the embedded Tomcat server
 * 5. Your application is now running and ready to accept HTTP requests!
 *
 * In the console, you'll see:
 *
 * ```
 * :: Spring Boot ::                (v3.2.2)
 *
 * 2024-01-28T16:00:00.000  INFO 1 --- [main] c.a.AnimeTrackerApplication : Starting AnimeTrackerApplication...
 * 2024-01-28T16:00:01.234  INFO 1 --- [main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat initialized with port 8080
 * 2024-01-28T16:00:02.345  INFO 1 --- [main] c.a.AnimeTrackerApplication : Started AnimeTrackerApplication in 2.567 seconds
 * ```
 *
 * This means the application is running and listening on http://localhost:8080
 */
