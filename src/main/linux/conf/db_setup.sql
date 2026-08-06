--0 lets assume db name is 'healing_house_clinic' and the user is 'hh_user'

-- 1. Create the database (if not already created)
CREATE DATABASE healing_house_clinic CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 2. Grant privileges
GRANT ALL PRIVILEGES ON healing_house_clinic.* TO 'hh_user'@'%';

-- 3. Apply the changes
FLUSH PRIVILEGES;