import * as React from "react";
import { LayoutContext, layoutAnimationProps, layoutAnimationTransition } from "./useLayoutState";
import { AnimatePresence, motion } from 'framer-motion';
import { RightSidebarResizeControl } from "./RightSidebarResizeControl";
import { XmarkIcon, ChevronDownVariableIcon, ArrowLeftOnBoxIcon } from '@/Icons/Icons';
import { Flex, ButtonGroup, Button, Text, IconButton, Box, Collapse, VStack, BoxProps, Tooltip } from '@chakra-ui/react';
import { useInView } from 'react-intersection-observer';
import { ErrorBoundary } from "react-error-boundary";

interface RightSidebarProps extends BoxProps {
  isOpen: boolean;
  onResize: (size: number) => void;
  rightSidebarWidth: number;
}

export const RightSidebar = (props: RightSidebarProps) => {
  const { children, onResize, isOpen } = props;
  const {
    toolbarHeight,
    isScrolledPastTitle,
    setIsScrolledPastTitle,
    isResizingLayout,
    unsavedRightSidebarWidth
  } = React.useContext(LayoutContext);

  const { ref: markerRef, inView } = useInView({ threshold: 0 });

  React.useEffect(() => {
    if (inView) {
      if (isScrolledPastTitle["rightSidebar"]) {
        setIsScrolledPastTitle(prev => ({ ...prev, "rightSidebar": false }));
      }
    } else {
      if (!isScrolledPastTitle["rightSidebar"]) {
        setIsScrolledPastTitle(prev => ({ ...prev, "rightSidebar": true }));
      }
    }
  }, [inView, setIsScrolledPastTitle]);

  const layoutAnimation = {
    ...layoutAnimationProps(unsavedRightSidebarWidth + "vw"),
    animate: {
      width: unsavedRightSidebarWidth + "vw",
      opacity: 1,
      transition: isResizingLayout ? {
        ...layoutAnimationTransition,
        mass: 0,
      } : layoutAnimationTransition
    },
  }

  return (
    <AnimatePresence initial={false}>
      {isOpen && (
        <Box
          as={motion.div}
          {...layoutAnimation}
          zIndex={1}
          bg="background.floor"
          transitionProperty="background"
          transitionTimingFunction="ease-in-out"
          transitionDuration="fast"
          borderLeft="1px solid"
          borderColor="separator.divider"
          height="var(--app-height)"
          position="fixed"
          id="right-sidebar"
          inset={0}
          left="auto"
        >
          <RightSidebarResizeControl
            onResizeSidebar={onResize}
          />
          <Box
            pt={`calc(${toolbarHeight} + 0.5rem)`}
            width={unsavedRightSidebarWidth + "vw"}
            overflowX="hidden"
            overflowY="auto"
            height="100%"
            position="relative"
            pb={10}
          >
            <Box
              aria-hidden
              position="absolute"
              ref={markerRef}
              height="20px"
              top={0}
            />
            {children}
          </Box>
        </Box>
      )}
    </AnimatePresence>
  );
};


export const SidebarItemBody = ({ isOpen, children }) => {
  return <Box
    as={Collapse}
    in={isOpen}
    animateOpacity
    unmountOnExit
    zIndex={1}
    px={4}
    sx={{
      // HACK: Gentle hack to create padding
      // within the collapse container, only
      // when open
      "> *:last-child": {
        mb: 2,
      }
    }}
    onPointerDown={(e) => { e.stopPropagation(); }}
  >
    {children}
  </Box>;
}
SidebarItemBody.defaultProps = {
  isOpen: true,
}

export const SidebarItemHeader = ({ children }) => {
  return <Flex
    alignItems="center"
    justifyContent="center"
  >{children}</Flex>
};

const SidebarItemFallback = ({ error, resetErrorBoundary }) => {
  return (
    <Box
      bg="background.floor"
      border="1px solid"
      borderColor="separator.divider"
      borderRadius="md"
      p={2}
      mx={4}
      mt={2}
      mb={4}
    >
      <Text mb={2}>An error occurred while rendering this sidebar item.</Text>
      <ButtonGroup size="xs">
        <Button
          onClick={resetErrorBoundary}
        >Try again</Button>
        <Button
        >Report issue</Button>
      </ButtonGroup>
    </Box>
  );
}

export const SidebarItemContainer = ({ children, ...props }) => {
  return <ErrorBoundary FallbackComponent={SidebarItemFallback}>
    <VStack
      bg="background.upper"
      borderRadius="md"
      overflow="hidden"
      align="stretch"
      position="relative"
      spacing={0}
      mx={4}
      sx={{
        "--page-padding": "0rem",
        "--page-left-gutter-width": "1em",
        "--page-right-gutter-width": "3em",
      }}
      mt={2}
      {...props}>
      {children}
    </VStack>
  </ErrorBoundary>
};

export const SidebarItemHeaderToggle = ({ children, isOpen, onToggle, ...props }) => {
  return <Button
    isDisabled={!onToggle}
    onClick={onToggle}
    colorScheme="subtle"
    justifyContent="flex-start"
    variant="ghost"
    size="sm"
    display="flex"
    flex="1 1 100%"
    borderRadius="0"
    gap={2}
    p={2}
    px={4}
    pr={20}
    height="auto"
    textAlign="left"
    overflow="hidden"
    whiteSpace="nowrap"
    leftIcon={onToggle ? <ChevronDownVariableIcon
      boxSize={4}
      mr={-2}
      transform={isOpen ? undefined : "rotate(-90deg)"}
      transitionProperty="common"
      transitionDuration="0.15s"
      transitionTimingFunction="ease-in-out"
      justifySelf="center" /> : null}
  >
    {children}
  </Button>
};
SidebarItemHeaderToggle.defaultProps = {
  isOpen: true,
}


export const SidebarItemTitle = ({ children, ...props }) => {
  return <Text
    noOfLines={0}
    overflow="hidden"
    textOverflow="ellipsis"
  >{children}</Text>
};


export const SidebarItemButtonGroup = ({ children, ...props }) => {
  return <ButtonGroup
    colorScheme="subtle"
    variant="ghost"
    size="xs"
    position="absolute"
    right={0}
    top={0}
    px={2}
    py={1}
  >{children}</ButtonGroup>
};

export const SidebarItem = ({ title, isOpen, onToggle, onRemove, onNavigate, children }) => {
  return (
    <SidebarItemContainer>
      <SidebarItemHeader>
        <SidebarItemHeaderToggle
          onToggle={onToggle}
          isOpen={isOpen}
        >
          <SidebarItemTitle
          >{title}</SidebarItemTitle>
        </SidebarItemHeaderToggle>
        <SidebarItemButtonGroup>
          <Tooltip label="Open">
            <IconButton
              onClick={onNavigate}
              aria-label="Close"
              icon={<ArrowLeftOnBoxIcon />}
            />
          </Tooltip>
          <Tooltip label="Remove from Sidebar">
            <IconButton
              onClick={onRemove}
              aria-label="Close"
              icon={<XmarkIcon />}
            />
          </Tooltip>
        </SidebarItemButtonGroup>
      </SidebarItemHeader>
      <SidebarItemBody isOpen={isOpen}>{children}</SidebarItemBody>
    </SidebarItemContainer>
  );
};
