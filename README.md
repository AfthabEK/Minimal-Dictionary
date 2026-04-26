### Material UI Dictionary

The aim of this project is to create a minimal, lightweight, offline dictionary app, with only the very basic features, using material 3 UI for Android. Using the wiktionary data, this app will provide meanings of words and idioms in English.


### Dataset

The dataset is sourced from the [Wiktionary](https://en.wiktionary.org/) dump, which is a comprehensive and freely available resource for word definitions and linguistic information. 

### Stages

1. **Data Extraction**: Extract relevant data from the wikitionary dump. Only words and idioms in English will be extracted, along with their meanings, parts of speech, and examples.

This is will be done using a Python script that parses the XML dump and extracts the necessary information.

2. **Data Cleaning and Formatting**: Convert the extracted data into a JSON file 
E.g:
```
{
  "term": "jib",
  "entries": [
    {
      "pos": "noun",
      "definitions": [
        {
          "text": "A triangular headsail set forward of the mast.",
          "examples": ["the light of the sun"]
        },
        {
          "text": "The arm of a crane.",
          "examples": []
        }
      ]
    }
  ]
}
```

3. **Data Storage**: Store the cleaned and formatted data in a local database (e.g., SQLite) that can be accessed by the Android app. This will allow for offline access to the dictionary data.

4. **App Development**: Develop the Android app using Material 3 UI. The app will have a simple interface where users can search for words and view their meanings and examples.



