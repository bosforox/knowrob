cmake_minimum_required(VERSION 2.8.3)
project(swrlprolog)

find_package(catkin REQUIRED COMPONENTS rosprolog knowrob_common)

catkin_package(CATKIN_DEPENDS rosprolog knowrob_common)
catkin_add_plunit(swrl)
  
install(DIRECTORY prolog/
  DESTINATION ${CATKIN_PACKAGE_SHARE_DESTINATION}/prolog)
