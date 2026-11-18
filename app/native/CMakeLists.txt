# libxfarchive : libarchive (BSD-2) réduit à la lecture des RAR 4, RAR 5 et ISO 9660, et son pont JNI.
# Compilé par build.sh (NDK pour Android, compilateur de la machine pour les tests JVM).
cmake_minimum_required(VERSION 3.16)
project(xfarchive C)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Rien d'autre que la bibliothèque : ni outils (bsdtar…), ni tests, ni chiffrement, ni compresseurs
# externes (les formats lus n'en ont pas besoin, sauf zlib pour les ISO compressés « zisofs »).
foreach(opt ENABLE_TEST ENABLE_TAR ENABLE_CPIO ENABLE_CAT ENABLE_UNZIP ENABLE_INSTALL ENABLE_WERROR
        ENABLE_OPENSSL ENABLE_MBEDTLS ENABLE_NETTLE ENABLE_CNG ENABLE_LIBB2 ENABLE_LZ4 ENABLE_LZO
        ENABLE_LZMA ENABLE_ZSTD ENABLE_BZip2 ENABLE_LIBXML2 ENABLE_EXPAT ENABLE_PCREPOSIX
        ENABLE_PCRE2POSIX ENABLE_LIBGCC ENABLE_XATTR ENABLE_ACL ENABLE_COVERAGE ENABLE_CLANG_TIDY)
  set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(ENABLE_ZLIB ON CACHE BOOL "" FORCE)
set(ENABLE_ICONV ON CACHE BOOL "" FORCE) # noms RAR 4 non Unicode (bionic : iconv depuis Android 9)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/libarchive EXCLUDE_FROM_ALL)

add_library(xfarchive SHARED xfarchive_jni.c)
target_include_directories(xfarchive PRIVATE third_party/libarchive/libarchive)
target_compile_options(xfarchive PRIVATE -Wall -Wextra -ffunction-sections -fdata-sections)
target_link_options(xfarchive PRIVATE -Wl,--gc-sections)
if(ANDROID)
  target_link_libraries(xfarchive PRIVATE archive_static)
else()
  find_package(JNI REQUIRED)
  target_include_directories(xfarchive PRIVATE ${JNI_INCLUDE_DIRS})
  target_link_libraries(xfarchive PRIVATE archive_static)
endif()
