# Yelp Clone

This project is a Java implementation of a review and social interaction platform. It includes features such as SMS login, session sharing with Redis, distributed locks, globally unique IDs, cache strategies, asynchronous order processing, social features, and more.

## Table of Contents

- [Introduction](#introduction)
- [Business Features](#business-features)
- [Technical Features](#technical-features)
- [Technology Stack](#technology-stack)
- [Setup](#setup)
- [Usage](#usage)
- [Contributing](#contributing)
- [License](#license)

## Introduction

The Yelp Clone project aims to provide a comprehensive platform for users to review businesses, interact socially, and participate in special promotions like flash sales. It leverages a variety of advanced technologies and architectural patterns to ensure scalability, reliability, and performance.

## Business Features

- **SMS Login:** Secure user login via SMS.
- **Coupon Flash Sales:** Implement coupon-based flash sales.
- **Follow Friends:** Social feature to follow other users.
- **Like Posts:** Allow users to like posts.
- **Ranking System:** Implement leaderboards and rankings.
- **Nearby Shops:** Use GEO operations to find nearby businesses.
- **User Check-ins:** Allow users to check-in at locations.
- **Feed Stream:** Implement feed streams with scrolling and pagination for inbox queries.

## Technical Features

- **Session Sharing:** Implement session sharing using Redis.
- **Distributed Locks:** Ensure data consistency with distributed locks.
- **Globally Unique IDs:** Generate unique IDs for distributed systems.
- **Cache Strategies:** Mitigate cache penetration, avalanche, and breakdown.
- **Lua Scripts:** Ensure atomicity of multiple Redis commands using Lua scripts.
- **Asynchronous Order Processing:** Use blocking queues to handle high-demand scenarios like flash sales.
- **Optimistic Locking:** Prevent overselling issues with optimistic locking.
- **Redisson Integration:** Simplify Redis operations with Redisson.

## Technology Stack

- **Backend:** Java, Spring Boot
- **Database:** MySQL
- **Cache:** Redis
- **Queue:** BlockingQueue
- **Locking:** Redisson
- **Messaging:** SMS Gateway (e.g., Twilio)
- **ID Generation:** Custom unique ID generator

## Setup

1. Clone the repository:
    ```sh
    git clone https://github.com/yourusername/yelp-clone.git
    cd yelp-clone
    ```

2. Ensure you have the necessary dependencies installed, including Java, Maven, Redis, and MySQL.

3. Configure your database and Redis settings in `application.properties`.

4. Build the project:
    ```sh
    mvn clean install
    ```

5. Run the application:
    ```sh
    mvn spring-boot:run
    ```

## Usage

Once the application is running, you can access it via your web browser and use the various features such as SMS login, checking in at locations, following friends, participating in flash sales, and more.

### Example Endpoints

- **Login:** `/api/login`
- **Check-in:** `/api/checkin`
- **Follow User:** `/api/follow`
- **Like Post:** `/api/like`
- **Nearby Shops:** `/api/shops/nearby`
- **Feed Stream:** `/api/feed`

## Contributing

Contributions are welcome! Please fork this repository and submit pull requests with your changes.

1. Fork the repository.
2. Create a new branch (`git checkout -b feature-branch`).
3. Commit your changes (`git commit -am 'Add new feature'`).
4. Push to the branch (`git push origin feature-branch`).
5. Create a new Pull Request.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
