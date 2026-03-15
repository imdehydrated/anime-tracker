import React from 'react';
import { Ionicons } from '@expo/vector-icons';
import { Tabs } from 'expo-router';

export default function TabLayout() {
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#e94560',
        tabBarInactiveTintColor: 'rgba(255,255,255,0.55)',
        tabBarStyle: {
          backgroundColor: '#12122a',
          borderTopColor: 'rgba(255,255,255,0.08)',
          height: 72,
          paddingTop: 8,
          paddingBottom: 10,
        },
        tabBarLabelStyle: {
          fontSize: 12,
          fontWeight: '600',
        },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: 'Home',
          tabBarIcon: ({ color }) => (
            <Ionicons name="home" size={22} color={color} />
          ),
        }}
      />
      <Tabs.Screen
        name="search"
        options={{
          title: 'Search',
          tabBarIcon: ({ color }) => (
            <Ionicons name="search" size={22} color={color} />
          ),
        }}
      />
      <Tabs.Screen
        name="smart-rec"
        options={{
          title: 'Smart Rec',
          tabBarIcon: ({ color }) => (
            <Ionicons name="sparkles" size={22} color={color} />
          ),
        }}
      />
      <Tabs.Screen
        name="my-list"
        options={{
          title: 'My List',
          tabBarIcon: ({ color }) => (
            <Ionicons name="bookmark" size={22} color={color} />
          ),
        }}
      />
    </Tabs>
  );
}
