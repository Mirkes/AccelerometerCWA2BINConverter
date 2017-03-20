cwa2bin <- function(fileName, deviceLocationCode = "left wrist"){
  command = paste("java cwa2bin",fileName,paste0("\"deviceLocationCode:",deviceLocationCode,"\""))
  system(command)
}