import os
import schedule
import time

# Function to run the Java program
def run_java_program():
    print("Running RoomBookingAutomation.jar...")
    # Replace with the actual path to your JAR file
    os.system('java -jar "/Users/kshitijmavai/Documents/roombooking/out/artifacts/roombooking_jar/roombooking.jar "')

# Schedule the task at 12:02 AM daily
schedule.every().day.at("00:02").do(run_java_program)

print("Scheduler is running...")

# Keep the script running in the background
while True:
    schedule.run_pending()
    time.sleep(1)
