# AccelerometerCWA2BINConverter

There are huge collection of accelerometer data in CWA files. For example in BioBank.

One of the best tool to work with accelerometer data is GGIR R package. Unfortunately this package does not work with CWA files. There are several ways to convert CWA files into WAV files but these convertors sometimes work incorrectly. 

The most natural file format for GGIR is BIN. There we develop java convertor from CWA to BIN format.

Developed class cwa2bin is used standard java classes only.

Usage:
java cwa2bin fileName arguments
where:

fileName is name of file to process. File name can contain wildcards but in this case this argument has to be quoted: "*.cwa" to process all cwa files in folder

deviceLocationCode:text contains text to include into Device Location Code field. If text contains at least one spaces or any other special symbols then all argument must be quoted: "deviceLocationCode:text with spaces"


Main assumption for conversion:
1. We read CWA file block by block before collection of 300 observation with specified frequency.
2. Temperature from the last read block is used for cirrent data page in bin file
3. Voltage of the battery at the last read block is used for cirrent data page in bin file
4. Light measurements are the same for all block of measurements (Light measurements is written ones per block and in each observations in Bin file).
5. In CWA file we consider frequency of measurements as a constant in each block. We resample measurements of x, y, and z acceleration from the observed frequency to the specified standard frequency by linear interpolation.
6. Last fragment of cwa data with less than 300 resampled measurements is ignored because Bin file can contain pages with 300 measurements only.
