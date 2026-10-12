cmake_minimum_required(VERSION 3.10)
project(cs16-client)

set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${CMAKE_CURRENT_SOURCE_DIR}/cmake/")

include(LibraryNaming)
# include(ExternalProject)

# for extras packaging
find_package(Python COMPONENTS Interpreter REQUIRED)

if (Python_FOUND)
	message(STATUS "Python interpreter found: ${Python_EXECUTABLE}")
else()
	message(FATAL_ERROR "Python not found. Please install Python.")
endif()

option(BUILD_CLIENT "Build client library." ON)
option(BUILD_MAINUI "Build menu library." ON)
option(BUILD_SERVER "Build server library." ON)
option(ENABLE_YY_THUNKS "Enable YY-Thunks for Windows XP compatibility." ON)

set(GAME_DIR "cstrike" CACHE STRING "Game directory name.")
set(CLIENT_INSTALL_DIR "cl_dlls" CACHE STRING "Client library location.")
set(SERVER_INSTALL_DIR "dlls" CACHE STRING "Server library location.")

# if(MSVC AND CMAKE_BUILD_TYPE STREQUAL "Debug")
# 	add_compile_options(/fsanitize=address)
# 	add_link_options(/fsanitize=address)
# endif()

if(VITA)
	message(STATUS "Building for PS Vita")
	add_compile_options(-fno-use-cxa-atexit)
endif()

if(NOT WIN32 AND NOT APPLE)
	add_link_options(-Wl,--no-undefined)
endif()

include(CheckSymbolExists)
check_symbol_exists(strlcpy string.h HAVE_STRLCPY)
check_symbol_exists(strlcat string.h HAVE_STRLCAT)

include(YYThunks)

if(BUILD_CLIENT)
	add_subdirectory(cl_dll)
	yy_thunks_apply(client)
endif()

# if(CMAKE_BUILD_TYPE STREQUAL "Debug")
# 	set(MESON_BUILD_TYPE debug)
# elseif(CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
# 	set(MESON_BUILD_TYPE debugoptimized)
# else()
# 	set(MESON_BUILD_TYPE release)
# endif()

if(BUILD_SERVER)
	# ExternalProject_Add(
	# 	yapb
	# 	SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/yapb
	# 	CONFIGURE_COMMAND meson setup --buildtype ${MESON_BUILD_TYPE} build
	# 	BUILD_COMMAND meson compile -C build
	# 	BUILD_ALWAYS 1
	# 	BUILD_IN_SOURCE 1
	# 	INSTALL_COMMAND meson install -C build --destdir ${CMAKE_INSTALL_PREFIX}
	# )
	# official yaPB source from the standalone clone (../yapb, fresh
	# github.com/yapb/yapb checkout with submodules) instead of the stale
	# 3rdparty/yapb submodule pin (2026-03-01). Out-of-tree source dirs
	# need an explicit binary dir argument.
	set(YAPB_LTO OFF CACHE BOOL "" FORCE)
	add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../yapb ${CMAKE_CURRENT_BINARY_DIR}/yapb)
	if(TARGET yapb AND CMAKE_CXX_COMPILER_ID MATCHES "Clang")
		# upstream configures its target with STRICT (-Werror); NDK clang
		# diagnostics can differ from upstream's CI toolchains, keep
		# warnings non-fatal for this build environment
		target_compile_options(yapb PRIVATE -Wno-error)
	endif()

	if(MSVC)
		# Limit YaPB to one concurrent compile job because MSVC shares one
		# compiler PDB between all source files of this target.
		set_property(GLOBAL APPEND PROPERTY JOB_POOLS yapb_compile_pool=1)
		set_property(TARGET yapb PROPERTY JOB_POOL_COMPILE yapb_compile_pool)
    endif()

	set_target_postfix(yapb)

	# CS-EBOT (kotelgg) as a second metamod bot plugin, built from the
	# standalone clone in ../CS-EBOT. The launcher UI exposes an
	# enable/disable switch for it below the YaPB one; output naming
	# follows the same scheme: libebot_android_<arch>.so
	add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../CS-EBOT/project ${CMAKE_CURRENT_BINARY_DIR}/ebot)
	set_target_postfix_with_name(EBOT ebot)

	set(XASH_COMPAT ON CACHE BOOL "" FORCE)
	add_subdirectory(3rdparty/ReGameDLL_CS)
	yy_thunks_apply(regamedll)
endif()

if(BUILD_MAINUI)
#	set(CS16CLIENT ON)
	# set(XASH_SDK ../../)
	add_subdirectory(3rdparty/mainui_cpp)
	yy_thunks_apply(menu)
endif()

set(GRAPHS_OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/graphs")

execute_process(
	COMMAND ${Python_EXECUTABLE}
	${CMAKE_CURRENT_SOURCE_DIR}/scripts/yapb_graph_dl.py
	${GRAPHS_OUTPUT}/addons/yapb/data/graph
)

set(EXTRAS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/cs16client-extras")
set(YAPB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../yapb/cfg")
set(EBOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../CS-EBOT")
# (bot assets incl. addons/ebot/waypoints are file(COPY)-ed at configure
# time - bump this comment to force a reconfigure when they change;
# r2: ebot_has_semiclip enabled in shipped ebot.cfg;
# r3: original-map waypoint pack added - 49 de_/cs_/as_/fy_ .ewp plus
# zm_army, curated de_dust2 restored - 70 .ewp ship in total)

if(ANDROID)
	set(EXTRAS_OUTPUT "${CMAKE_CURRENT_SOURCE_DIR}/android/app/src/main/assets")

	# Preserve the AMX Mod X bundle (addons.zip + addons_version.txt) from
	# being wiped by REMOVE_RECURSE below: stash it, restore after the copy.
	set(AMXX_STASH "${CMAKE_CURRENT_BINARY_DIR}/amxx_assets_stash")
	set(AMXX_FILES "")
	foreach(AMXX_FILE addons.zip addons_version.txt)
		if(EXISTS "${EXTRAS_OUTPUT}/${AMXX_FILE}")
			list(APPEND AMXX_FILES "${EXTRAS_OUTPUT}/${AMXX_FILE}")
		endif()
	endforeach()

	file(GLOB EXTRAS_FILES "${EXTRAS_DIR}/*")
	file(GLOB YAPB_FILES "${YAPB_DIR}/*" "${GRAPHS_OUTPUT}/*")
	# EBot configs (addons/ebot/*.cfg) ship as individual assets too; the
	# launcher copies them - like the YaPB pack - onto disk, because the bot
	# reads them with plain libc fopen(). Glob the ADDONS root so the copied
	# directory keeps its name and lands at assets/addons/ebot/...
	file(GLOB EBOT_FILES "${EBOT_DIR}/addons")

	if(AMXX_FILES)
		file(REMOVE_RECURSE ${AMXX_STASH})
		file(MAKE_DIRECTORY ${AMXX_STASH})
		file(COPY ${AMXX_FILES} DESTINATION ${AMXX_STASH})
	endif()

	file(REMOVE_RECURSE ${EXTRAS_OUTPUT})
	file(
		COPY ${EXTRAS_FILES} ${YAPB_FILES} ${EBOT_FILES}
		DESTINATION ${EXTRAS_OUTPUT}
	)

	# restore the AMXX bundle into the assets directory
	file(GLOB AMXX_STASHED_FILES "${AMXX_STASH}/*")
	if(AMXX_STASHED_FILES)
		file(COPY ${AMXX_STASHED_FILES} DESTINATION ${EXTRAS_OUTPUT})
	endif()
else()
	set(EXTRAS_OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/extras.pk3")

	execute_process(
		COMMAND ${Python_EXECUTABLE}
		${CMAKE_CURRENT_SOURCE_DIR}/scripts/pack_extras.py
		${EXTRAS_OUTPUT}
		${EXTRAS_DIR}
		${YAPB_DIR}
		${GRAPHS_OUTPUT}
	)

	add_custom_target(generate_extras ALL
		DEPENDS ${GRAPHS_OUTPUT} ${EXTRAS_OUTPUT}
	)

	install(
		FILES "${EXTRAS_OUTPUT}"
		DESTINATION ${GAME_DIR}
	)
endif()

if(VITA)
	install(
		FILES
		"${CMAKE_CURRENT_BINARY_DIR}/config.cfg"
		"${CMAKE_CURRENT_BINARY_DIR}/video.cfg"
		"${CMAKE_CURRENT_BINARY_DIR}/opengl.cfg"
		DESTINATION "${GAME_DIR}/"
	)

	install(
		FILES
		"${CMAKE_CURRENT_BINARY_DIR}/kb_def.lst"
		DESTINATION "${GAME_DIR}/gfx/shell"
	)
endif()

if(WIN32)
	set(CPACK_GENERATOR "ZIP")
	set(CPACK_PACKAGE_FILE_NAME "CS16Client-${CMAKE_SYSTEM_NAME}-${CMAKE_CXX_COMPILER_ARCHITECTURE_ID}")
elseif(VITA)
	set(CPACK_GENERATOR "ZIP")
	set(CPACK_PACKAGE_FILE_NAME "CS16Client-PSVita")
elseif(APPLE)
	set(CPACK_GENERATOR "ZIP")
	set(CPACK_PACKAGE_FILE_NAME "CS16Client-macOS-${CMAKE_SYSTEM_PROCESSOR}")
else()
	set(CPACK_GENERATOR "TGZ")
	set(CPACK_PACKAGE_FILE_NAME "CS16Client-${CMAKE_SYSTEM_NAME}-${CMAKE_SYSTEM_PROCESSOR}")
endif()

set(CPACK_INCLUDE_TOPLEVEL_DIRECTORY OFF)

include(CPack)