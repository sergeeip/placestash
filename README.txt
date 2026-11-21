PlaceStash - bait stash that catches x-rayers

WHAT IT DOES
  /placestash          buries a spawner + candle cake inside solid rock below you
  /placestash list     shows active stashes
  /placestash remove   removes the nearest stash (within 10 blocks)
  When anyone breaks it, staff with placestash.alert get a chat alert and the
  name is saved to plugins/PlaceStash/caught.log.
  Staff with placestash.exempt can dig one up without triggering an alert.

GET THE JAR WITHOUT INSTALLING ANYTHING (GitHub builds it for you)
  1. Make a free account + new repository at github.com
  2. Upload everything in this folder (click "Add file" > "Upload files")
  3. IMPORTANT: the hidden .github folder must be included. If it did not upload,
     click "Add file" > "Create new file", name it  .github/workflows/build.yml
     and paste the contents of build.yml from this zip.
  4. Open the "Actions" tab, wait for the green check, open the run,
     download the "PlaceStash" artifact (a zip), unzip it -> PlaceStash.jar
  5. Put PlaceStash.jar in Falix /plugins and restart.
