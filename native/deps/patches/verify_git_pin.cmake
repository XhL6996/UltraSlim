# Fails the build when a pinned git dependency does not resolve to the recorded commit.
#
# CMake's ExternalProject rejects GIT_SHALLOW together with a commit hash, so the
# dependency keeps a cheap shallow clone of a tag and the commit is verified here
# instead. A moved tag then breaks the build loudly rather than silently changing
# what gets linked into the APK.
#
# Invoked as: cmake -DSRC_DIR=<checkout> -DEXPECTED=<sha> -P verify_git_pin.cmake

if(NOT SRC_DIR OR NOT EXPECTED)
  message(FATAL_ERROR "verify_git_pin.cmake requires SRC_DIR and EXPECTED")
endif()

execute_process(
  COMMAND git -C "${SRC_DIR}" rev-parse HEAD
  OUTPUT_VARIABLE ACTUAL
  OUTPUT_STRIP_TRAILING_WHITESPACE
  RESULT_VARIABLE RESULT)
if(NOT RESULT EQUAL 0)
  message(FATAL_ERROR "Unable to read the checked-out commit in ${SRC_DIR}")
endif()

if(NOT ACTUAL STREQUAL EXPECTED)
  message(FATAL_ERROR
    "Pinned dependency moved: ${SRC_DIR}\n"
    "  expected ${EXPECTED}\n"
    "  actual   ${ACTUAL}\n"
    "Update the pin deliberately if the new commit is intended.")
endif()
